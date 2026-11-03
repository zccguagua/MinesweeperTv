package com.example.minesweeper

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
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
    private lateinit var leaderboard: LeaderboardStore

    private var difficulty = Difficulty.NORMAL
    private var elapsedSeconds = 0
    private var running = false

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (running) {
                elapsedSeconds++
                updateTimer()
                handler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        leaderboard = LeaderboardStore(this)
        buildUi()
        startNewGame(difficulty)
    }

    // --- UI construction --------------------------------------------------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(COLOR_BG))
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }

        // Top information bar.
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        mineText = makeLabel("", 20f).apply { setTextColor(Color.WHITE) }
        topBar.addView(mineText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        difficultyText = makeLabel("", 26f).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(COLOR_ACCENT))
        }
        topBar.addView(difficultyText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        timerText = makeLabel("", 20f).apply {
            gravity = Gravity.END
            setTextColor(Color.WHITE)
        }
        topBar.addView(timerText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Board.
        board = BoardView(this).apply { listener = this@MainActivity }
        val boardContainer = FrameLayout(this)
        boardContainer.addView(board, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(boardContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Controls hint.
        val hint = makeLabel(getString(R.string.controls_hint), 13f).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(COLOR_HINT))
        }
        root.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Bottom action bar.
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        bottomBar.addView(makeButton(getString(R.string.btn_new_game)) { startNewGame(difficulty) })
        bottomBar.addView(makeButton(getString(R.string.btn_difficulty)) { showDifficultyDialog() })
        bottomBar.addView(makeButton(getString(R.string.btn_leaderboard)) { showLeaderboard() })
        root.addView(bottomBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
    }

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
    }

    // --- Game flow --------------------------------------------------------

    private fun startNewGame(diff: Difficulty) {
        difficulty = diff
        board.newGame(diff)
        elapsedSeconds = 0
        running = false
        handler.removeCallbacks(ticker)
        updateTimer()
        updateDifficultyLabel()
        refreshMines()
        board.requestFocus()
    }

    private fun startTimer() {
        if (running) return
        running = true
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 1000L)
    }

    private fun stopTimer() {
        running = false
        handler.removeCallbacks(ticker)
    }

    private fun refreshMines() {
        mineText.text = "💣 ${getString(R.string.mines_label)} ${board.minesRemaining()}"
    }

    private fun updateDifficultyLabel() {
        difficultyText.text = difficulty.displayName
    }

    private fun updateTimer() {
        timerText.text = "⏱ ${formatTime(elapsedSeconds)}"
    }

    // --- BoardView.Listener -----------------------------------------------

    override fun onReveal() {
        startTimer()
        refreshMines()
    }

    override fun onFlagChange() {
        refreshMines()
    }

    override fun onGameWon() {
        stopTimer()
        val rank = leaderboard.addEntry(difficulty, elapsedSeconds)
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
    }

    override fun onGameLost() {
        stopTimer()
        val message = "难度：${difficulty.displayName}\n用时：${formatTime(elapsedSeconds)}"
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_lose_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.dialog_play_again)) { _, _ -> startNewGame(difficulty) }
            .setNegativeButton(getString(R.string.dialog_close), null)
            .setCancelable(false)
            .show()
    }

    // --- Dialogs ----------------------------------------------------------

    private fun showDifficultyDialog() {
        val names = Difficulty.entries
            .map { "${it.displayName}　${it.cols}×${it.rows} · ${it.mineCount}雷" }
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_choose_difficulty))
            .setItems(names) { _, which -> startNewGame(Difficulty.entries[which]) }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
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
        AlertDialog.Builder(this)
            .setTitle("${getString(R.string.btn_leaderboard)} · ${difficulty.displayName}")
            .setMessage(message)
            .setPositiveButton(getString(R.string.dialog_ok), null)
            .show()
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
    }
}
