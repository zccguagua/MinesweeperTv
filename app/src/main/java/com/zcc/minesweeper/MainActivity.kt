package com.zcc.minesweeper

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), BoardView.Listener {

    private lateinit var board: BoardView
    private lateinit var mineText: TextView
    private lateinit var timerText: TextView
    private lateinit var difficultyText: TextView
    private lateinit var resultText: TextView
    private lateinit var padHint: TextView
    private lateinit var mouseHint: TextView
    private lateinit var newGameButton: Button
    private lateinit var inputDebug: DebugInputOverlay
    private lateinit var leaderboard: LeaderboardStore
    private lateinit var sounds: SoundPlayer

    private var inputDebugVisible = SHOW_INPUT_DEBUG

    private var difficulty = Difficulty.NORMAL

    /** Whole seconds accumulated before the current timed segment. */
    private var elapsedSeconds = 0

    /** Monotonic timestamp the current segment started at. */
    private var segmentStart = 0L
    private var running = false

    /** Whether the clock should resume when the activity comes back to the foreground. */
    private var resumeTimerOnForeground = false

    /** Whether the clock was running when a menu dialog was opened. */
    private var clockPausedForDialog = false

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            updateTimer()
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        leaderboard = LeaderboardStore(this)
        sounds = SoundPlayer(this)
        buildUi()
        startNewGame(difficulty)
    }

    override fun onDestroy() {
        sounds.release()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        updateInputHints()
        if (resumeTimerOnForeground) {
            resumeTimerOnForeground = false
            startTimer()
        }
    }

    override fun onPause() {
        super.onPause()
        // Leaving the app must not keep the clock running.
        resumeTimerOnForeground = running
        stopTimer()
        updateTimer()
    }

    /** Everything a key event does, plus the input diagnostics readout. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_INFO) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) toggleInputDebug()
            return true
        }
        if (inputDebugVisible) inputDebug.logKey(event)
        return super.dispatchKeyEvent(event)
    }

    private fun toggleInputDebug() {
        inputDebugVisible = !inputDebugVisible
        inputDebug.visibility = if (inputDebugVisible) View.VISIBLE else View.GONE
    }

    /**
     * A gamepad may be plugged in while the activity is already running, so the
     * gamepad hint is also revealed as soon as joystick input shows up.
     *
     * Motion also has to be translated into DPAD_* keys for everything that is
     * not the board: hat axes produce no key events of their own, so dialogs and
     * the side panel buttons would otherwise be unreachable from a pad.
     */
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (GamepadInput.isGamepadSource(ev.source)) {
            if (padHint.visibility != View.VISIBLE) padHint.visibility = View.VISIBLE
            if (inputDebugVisible) inputDebug.logMotion(ev)
            if (currentFocus !== board && forwardPadNavigation(ev) { dispatchKeyEvent(it) }) {
                return true
            }
        } else if (isMouseSource(ev.source)) {
            if (mouseHint.visibility != View.VISIBLE) mouseHint.visibility = View.VISIBLE
            if (inputDebugVisible) inputDebug.logMouse(ev)
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    private var padFocusDir = GamepadInput.NEUTRAL
    private var padFocusTime = 0L

    /**
     * Turns a d-pad/stick direction into a one-shot DPAD_* key press, so that the
     * normal focus navigation of buttons and dialogs works with a gamepad.
     *
     * @return true when the event was consumed.
     */
    private fun forwardPadNavigation(ev: MotionEvent, dispatch: (KeyEvent) -> Boolean): Boolean {
        if (ev.actionMasked != MotionEvent.ACTION_MOVE) return false

        val dir = GamepadInput.direction(ev)
        if (dir == padFocusDir) return true
        padFocusDir = dir
        if (dir == GamepadInput.NEUTRAL) return true

        val now = SystemClock.uptimeMillis()
        if (now - padFocusTime < FOCUS_NAV_INTERVAL_MS) return true
        padFocusTime = now

        val (dx, dy) = dir
        val keyCode = when {
            dx < 0 -> KeyEvent.KEYCODE_DPAD_LEFT
            dx > 0 -> KeyEvent.KEYCODE_DPAD_RIGHT
            dy < 0 -> KeyEvent.KEYCODE_DPAD_UP
            else -> KeyEvent.KEYCODE_DPAD_DOWN
        }
        dispatch(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        dispatch(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return true
    }

    /** Makes an alert dialog navigable with a gamepad d-pad/stick. */
    private fun AlertDialog.enableGamepadNavigation() {
        val win = window ?: return
        val inner = win.callback ?: return
        win.callback = object : Window.Callback by inner {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (inputDebugVisible) inputDebug.logKey(event)
                return inner.dispatchKeyEvent(event)
            }

            override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
                if (GamepadInput.isGamepadSource(ev.source) &&
                    forwardPadNavigation(ev) { dispatchKeyEvent(it) }
                ) {
                    return true
                }
                return inner.dispatchGenericMotionEvent(ev)
            }
        }
    }

    private fun updateInputHints() {
        var gamepad = false
        var mouse = false
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            if (GamepadInput.isGamepadSource(device.sources)) gamepad = true
            if (isMouseSource(device.sources)) mouse = true
        }
        padHint.visibility = if (gamepad) View.VISIBLE else View.GONE
        mouseHint.visibility = if (mouse) View.VISIBLE else View.GONE
    }

    private fun isMouseSource(source: Int): Boolean =
        source and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE

    // --- UI construction --------------------------------------------------

    private fun buildUi() {
        // The screen is landscape, so the space that is left over after the board
        // gets a square grid is on the sides - not above or below it. Everything
        // that is not the board therefore lives in a panel on the right.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor(COLOR_BG))
            setPadding(dp(24), dp(16), dp(16), dp(16))
        }

        // Board: takes every pixel the side panel does not need.
        board = BoardView(this).apply { listener = this@MainActivity }

        // Input diagnostics on the left, only while it is switched on.
        inputDebug = DebugInputOverlay(this).apply {
            visibility = if (inputDebugVisible) View.VISIBLE else View.GONE
        }
        root.addView(
            inputDebug,
            LinearLayout.LayoutParams(debugWidthPx(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginEnd = dp(8)
            }
        )

        root.addView(board, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        // Side information panel.
        val sidePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Text block. Weight 1 keeps the buttons below from ever being pushed
        // off-screen on short panels; the text is what gets clipped instead.
        val infoPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        difficultyText = makeLabel("", 28f).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(COLOR_ACCENT))
        }
        infoPanel.addView(difficultyText, itemParams())

        mineText = makeLabel("", 20f).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        infoPanel.addView(mineText, itemParams())

        timerText = makeLabel("", 20f).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        infoPanel.addView(timerText, itemParams())

        resultText = makeLabel("", 15f).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(COLOR_ACCENT))
        }
        infoPanel.addView(resultText, itemParams(top = 12))

        val hint = makeLabel(getString(R.string.controls_hint), 13f).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(COLOR_HINT))
        }
        infoPanel.addView(hint, itemParams(top = 12))

        padHint = makeLabel(getString(R.string.controls_hint_gamepad), 13f).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(COLOR_HINT))
        }
        infoPanel.addView(padHint, itemParams(top = 8))

        mouseHint = makeLabel(getString(R.string.controls_hint_mouse), 13f).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(COLOR_HINT))
        }
        infoPanel.addView(mouseHint, itemParams(top = 8))

        sidePanel.addView(infoPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        newGameButton = makeButton(getString(R.string.btn_new_game)) { startNewGame(difficulty) }
        sidePanel.addView(newGameButton, itemParams(top = 8))
        sidePanel.addView(makeButton(getString(R.string.btn_difficulty)) { showDifficultyDialog() }, itemParams(top = 8))
        sidePanel.addView(makeButton(getString(R.string.btn_leaderboard)) { showLeaderboard() }, itemParams(top = 8))

        root.addView(
            sidePanel,
            LinearLayout.LayoutParams(panelWidthPx(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginStart = dp(16)
            }
        )

        setContentView(root)
    }

    private fun panelWidthPx(): Int =
        (resources.displayMetrics.widthPixels * PANEL_WIDTH_RATIO).toInt()

    private fun debugWidthPx(): Int =
        (resources.displayMetrics.widthPixels * DEBUG_PANEL_WIDTH_RATIO).toInt()

    private fun itemParams(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(top) }

    private fun makeLabel(text: String, sp: Float): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(4), dp(8), dp(4), dp(8))
    }

    private fun makeButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(Color.WHITE)
        isAllCaps = false
        minHeight = dp(48)
        minWidth = dp(150)
        setPadding(dp(20), dp(8), dp(20), dp(8))
        setOnClickListener { onClick() }
        // Gamepad A does not click a focused button on its own.
        setOnKeyListener { v, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                v.performClick()
                true
            } else {
                false
            }
        }
    }

    // --- Game flow --------------------------------------------------------

    private fun startNewGame(diff: Difficulty) {
        difficulty = diff
        board.newGame(diff)
        elapsedSeconds = 0
        running = false
        resumeTimerOnForeground = false
        clockPausedForDialog = false
        handler.removeCallbacks(ticker)
        resultText.text = ""
        updateTimer()
        updateDifficultyLabel()
        refreshMines()
        board.requestFocus()
    }

    private fun startTimer() {
        if (running) return
        running = true
        segmentStart = SystemClock.elapsedRealtime()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun stopTimer() {
        if (running) {
            elapsedSeconds = currentSeconds()
            running = false
        }
        handler.removeCallbacks(ticker)
    }

    /** Freezes the clock while a menu dialog (difficulty, leaderboard) is open. */
    private fun pauseTimerForDialog() {
        clockPausedForDialog = running
        stopTimer()
        updateTimer()
    }

    private fun resumeTimerAfterDialog() {
        if (!clockPausedForDialog) return
        clockPausedForDialog = false
        startTimer()
    }

    /**
     * Whole seconds played. Derived from the monotonic clock instead of counting
     * ticks, so the displayed time cannot drift away from the real duration.
     */
    private fun currentSeconds(): Int {
        val live = if (running) {
            ((SystemClock.elapsedRealtime() - segmentStart) / 1000L).toInt()
        } else {
            0
        }
        return elapsedSeconds + live
    }

    private fun refreshMines() {
        mineText.text = "💣 ${getString(R.string.mines_label)} ${board.minesRemaining()}"
    }

    private fun updateDifficultyLabel() {
        difficultyText.text = difficulty.displayName
    }

    private fun updateTimer() {
        timerText.text = "⏱ ${formatTime(currentSeconds())}"
    }

    // --- BoardView.Listener -----------------------------------------------

    override fun onReveal() {
        startTimer()
        refreshMines()
        sounds.playReveal()
    }

    override fun onFlagChange() {
        refreshMines()
        sounds.playFlag()
    }

    override fun onMenuRequested() {
        newGameButton.requestFocus()
    }

    override fun onGameWon() {
        stopTimer()
        sounds.playWin()
        val rank = leaderboard.addEntry(difficulty, elapsedSeconds)
        resultText.setTextColor(Color.parseColor(COLOR_ACCENT))
        resultText.text = buildString {
            append("🎉 ").append(getString(R.string.dialog_win_title))
            append('\n').append("用时 ").append(formatTime(elapsedSeconds))
            if (rank > 0) {
                append('\n').append("🎖 排行榜第 ").append(rank).append(" 名")
            }
        }
        val message = buildString {
            append("难度：").append(difficulty.displayName).append('\n')
            append("用时：").append(formatTime(elapsedSeconds))
            if (rank > 0) {
                append("\n\n🎖 进入排行榜第 ").append(rank).append(" 名！")
            }
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_win_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.dialog_play_again)) { _, _ -> startNewGame(difficulty) }
            .setNegativeButton(getString(R.string.dialog_close), null)
            .setCancelable(false)
            .show()
            .enableGamepadNavigation()
    }

    override fun onGameLost() {
        stopTimer()
        sounds.playLose()
        resultText.setTextColor(Color.parseColor(COLOR_LOSE))
        resultText.text = "💥 " + getString(R.string.dialog_lose_title) +
            "\n用时 " + formatTime(elapsedSeconds)
        val message = "难度：${difficulty.displayName}\n用时：${formatTime(elapsedSeconds)}"
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_lose_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.dialog_play_again)) { _, _ -> startNewGame(difficulty) }
            .setNegativeButton(getString(R.string.dialog_close), null)
            .setCancelable(false)
            .show()
            .enableGamepadNavigation()
    }

    // --- Dialogs ----------------------------------------------------------

    private fun showDifficultyDialog() {
        val names = Difficulty.entries
            .map { "${it.displayName}　${it.cols}×${it.rows} · ${it.mineCount}雷" }
            .toTypedArray()
        pauseTimerForDialog()
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_choose_difficulty))
            .setItems(names) { _, which -> startNewGame(Difficulty.entries[which]) }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
        dialog.enableGamepadNavigation()
        dialog.setOnDismissListener { resumeTimerAfterDialog() }
    }

    private fun showLeaderboard() {
        val entries = leaderboard.getEntries(difficulty)
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val message = buildString {
            if (entries.isEmpty()) {
                append(getString(R.string.leaderboard_empty))
            } else {
                entries.forEachIndexed { index, entry ->
                    append(index + 1).append(".  ")
                    append(formatTime(entry.seconds)).append("   ")
                    append(formatter.format(Date(entry.dateMillis)))
                    append('\n')
                }
            }
        }
        pauseTimerForDialog()
        val dialog = AlertDialog.Builder(this)
            .setTitle("${getString(R.string.btn_leaderboard)} · ${difficulty.displayName}")
            .setMessage(message)
            .setPositiveButton(getString(R.string.dialog_ok), null)
            .show()
        dialog.enableGamepadNavigation()
        dialog.setOnDismissListener { resumeTimerAfterDialog() }
    }

    // --- Helpers ----------------------------------------------------------

    private fun formatTime(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val COLOR_BG = "#1B1B1B"
        private const val COLOR_ACCENT = "#FFD600"
        private const val COLOR_HINT = "#9E9E9E"
        private const val COLOR_LOSE = "#EF5350"

        /** Share of the screen width reserved for the side panel. */
        private const val PANEL_WIDTH_RATIO = 0.26f

        /** Share of the screen width reserved for the input diagnostics panel. */
        private const val DEBUG_PANEL_WIDTH_RATIO = 0.2f

        /** How often the timer label is refreshed; the value itself is exact. */
        private const val TICK_INTERVAL_MS = 250L

        /** Set to false to ship without the input diagnostics overlay. */
        private const val SHOW_INPUT_DEBUG = true

        /** Minimum gap between d-pad-driven focus moves outside the board. */
        private const val FOCUS_NAV_INTERVAL_MS = 140L
    }
}
