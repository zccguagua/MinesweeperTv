package com.example.minesweeper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.min

/**
 * Renders the board and handles all three input sources:
 *
 *  - Touch: tap to reveal, long-press to flag.
 *  - Mouse: left click to reveal, right click to flag, hover to move cursor.
 *  - Remote/gamepad: D-pad moves the cursor, OK/Enter reveals, and
 *    Menu/B/Y (or long-press OK) toggles a flag.
 */
class BoardView(context: Context) : View(context) {

    interface Listener {
        fun onReveal()
        fun onFlagChange()
        fun onGameWon()
        fun onGameLost()

        /** Gamepad START: hand focus to the side panel so the buttons are reachable. */
        fun onMenuRequested()
    }

    var listener: Listener? = null

    var difficulty: Difficulty = Difficulty.NORMAL
        private set

    private var game = MinesweeperGame(difficulty.cols, difficulty.rows, difficulty.mineCount)

    private var cursorCol = 0
    private var cursorRow = 0

    // Board geometry, recomputed in onSizeChanged.
    private var cellSize = 0f
    private var boardLeft = 0f
    private var boardTop = 0f

    // Long-press detection for the remote OK button (reveal vs flag).
    private var centerLongPressed = false
    private val centerLongPress = Runnable {
        centerLongPressed = true
        performFlag(cursorCol, cursorRow)
    }

    // Gamepad state: direction currently held on the d-pad/stick, plus the time of
    // the last cursor move caused by a DPAD_* key so the two paths can be told apart.
    private var padDir = GamepadInput.NEUTRAL
    private var lastKeyMoveTime = 0L

    private val padRepeat = object : Runnable {
        override fun run() {
            if (padDir == GamepadInput.NEUTRAL) return
            if (stepCursor(padDir.first, padDir.second)) {
                postDelayed(this, GAMEPAD_REPEAT_MS)
            } else {
                padDir = GamepadInput.NEUTRAL
            }
        }
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            cellAtPosition(e.x, e.y)?.let { (c, r) -> performReveal(c, r) }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            cellAtPosition(e.x, e.y)?.let { (c, r) -> performFlag(c, r) }
        }
    })

    // --- Paints -----------------------------------------------------------
    private val bgPaint = Paint().apply { color = COLOR_BOARD_BG }
    private val raisedPaint = Paint().apply { color = COLOR_RAISED }
    private val lightPaint = Paint().apply { color = COLOR_LIGHT }
    private val darkPaint = Paint().apply { color = COLOR_DARK }
    private val flatPaint = Paint().apply { color = COLOR_FLAT }
    private val flatBorderPaint = Paint().apply {
        color = COLOR_FLAT_BORDER
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val explodedPaint = Paint().apply { color = COLOR_EXPLODED }
    private val wrongFlagPaint = Paint().apply {
        color = COLOR_WRONG_FLAG
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val cursorPaint = Paint().apply {
        color = COLOR_CURSOR
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val minePaint = Paint().apply {
        isAntiAlias = true
        color = COLOR_MINE
        style = Paint.Style.FILL
    }
    private val spikePaint = Paint().apply {
        isAntiAlias = true
        color = COLOR_MINE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val polePaint = Paint().apply {
        isAntiAlias = true
        color = COLOR_POLE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val flagPaint = Paint().apply {
        isAntiAlias = true
        color = COLOR_FLAG
        style = Paint.Style.FILL
    }
    private val flagPath = Path()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun newGame(diff: Difficulty) {
        difficulty = diff
        game = MinesweeperGame(diff.cols, diff.rows, diff.mineCount)
        cursorCol = 0
        cursorRow = 0
        centerLongPressed = false
        padDir = GamepadInput.NEUTRAL
        removeCallbacks(centerLongPress)
        removeCallbacks(padRepeat)
        updateBoardMetrics()
        invalidate()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) {
            padDir = GamepadInput.NEUTRAL
            removeCallbacks(padRepeat)
        }
    }

    fun restart() = newGame(difficulty)

    /** Number of mines not yet flagged. */
    fun minesRemaining(): Int = game.minesRemaining()

    // --- Input ------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Touchscreen events only; mouse/trackpad clicks are handled in
        // onGenericMotionEvent to avoid double-handling a single click.
        if (event.source and InputDevice.SOURCE_TOUCHSCREEN == 0) {
            return super.onTouchEvent(event)
        }
        // Keep the cursor following the pointer for visual feedback.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_HOVER_MOVE -> {
                cellAtPosition(event.x, event.y)?.let { (c, r) ->
                    if (c != cursorCol || r != cursorRow) {
                        cursorCol = c
                        cursorRow = r
                        invalidate()
                    }
                }
            }
        }
        return gestureDetector.onTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (GamepadInput.isGamepadSource(event.source)) {
            return handleGamepadMotion(event)
        }
        if (event.source and InputDevice.SOURCE_MOUSE == 0) {
            return super.onGenericMotionEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> {
                // Mouse users don't move focus with a d-pad, so take focus here:
                // the cursor highlight is only drawn while the board has it.
                if (!hasFocus()) requestFocus()
                cellAtPosition(event.x, event.y)?.let { (c, r) ->
                    if (c != cursorCol || r != cursorRow) {
                        cursorCol = c
                        cursorRow = r
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_BUTTON_PRESS -> {
                if (!hasFocus()) requestFocus()
                val cell = cellAtPosition(event.x, event.y)
                when (event.actionButton) {
                    MotionEvent.BUTTON_PRIMARY -> cell?.let { (c, r) -> performReveal(c, r) }
                    MotionEvent.BUTTON_SECONDARY -> cell?.let { (c, r) -> performFlag(c, r) }
                }
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                lastKeyMoveTime = SystemClock.uptimeMillis()
                if (cursorRow > 0) {
                    cursorRow--
                    invalidate()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                lastKeyMoveTime = SystemClock.uptimeMillis()
                if (cursorRow < game.rows - 1) {
                    cursorRow++
                    invalidate()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                lastKeyMoveTime = SystemClock.uptimeMillis()
                if (cursorCol > 0) {
                    cursorCol--
                    invalidate()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                lastKeyMoveTime = SystemClock.uptimeMillis()
                if (cursorCol < game.cols - 1) {
                    cursorCol++
                    invalidate()
                    return true
                }
                // At the right edge let focus move to the side panel.
                return false
            }

            KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (event.repeatCount == 0) {
                    centerLongPressed = false
                    removeCallbacks(centerLongPress)
                    postDelayed(centerLongPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                return true
            }

            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                // Held buttons repeat on some pads; reveal once per press.
                if (event.repeatCount == 0) performReveal(cursorCol, cursorRow)
                return true
            }

            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_PROG_RED,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (event.repeatCount == 0) performFlag(cursorCol, cursorRow)
                return true
            }

            KeyEvent.KEYCODE_BUTTON_START -> {
                if (event.repeatCount == 0) listener?.onMenuRequested()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            removeCallbacks(centerLongPress)
            if (!centerLongPressed) {
                performReveal(cursorCol, cursorRow)
            }
            centerLongPressed = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // --- Gamepad ----------------------------------------------------------

    /**
     * Xbox/PS style pads report the d-pad as hat axes and the left stick as X/Y
     * axes, so directional input arrives here instead of as key events.
     * Keyboard-style pads only send DPAD_* keys and never reach this method.
     */
    private fun handleGamepadMotion(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return true

        val dir = GamepadInput.direction(event)

        // Some pads report the d-pad *both* as a hat axis and as DPAD_* keys.
        // Ignore the axis while the key events are already driving the cursor.
        if (dir != GamepadInput.NEUTRAL &&
            SystemClock.uptimeMillis() - lastKeyMoveTime < GAMEPAD_KEY_DEDUPE_MS
        ) {
            return true
        }

        if (dir == padDir) return true

        padDir = dir
        removeCallbacks(padRepeat)
        if (dir != GamepadInput.NEUTRAL) {
            if (stepCursor(dir.first, dir.second)) {
                postDelayed(padRepeat, GAMEPAD_REPEAT_MS)
            } else {
                padDir = GamepadInput.NEUTRAL
            }
        }
        return true
    }

    /** Moves the cursor by one cell. Returns false when it hit the board edge. */
    private fun stepCursor(dx: Int, dy: Int): Boolean {
        if (dx != 0) {
            val target = cursorCol + dx
            if (target in 0 until game.cols) {
                cursorCol = target
                invalidate()
                return true
            }
            // Mirror the remote: pressing right at the right edge focuses the panel.
            if (dx > 0) focusSearch(View.FOCUS_RIGHT)?.requestFocus()
            return false
        }
        if (dy != 0) {
            val target = cursorRow + dy
            if (target in 0 until game.rows) {
                cursorRow = target
                invalidate()
                return true
            }
        }
        return false
    }

    // --- Actions ----------------------------------------------------------

    private fun performReveal(col: Int, row: Int) {
        if (game.isOver) return
        when (game.reveal(col, row)) {
            RevealResult.LOST -> {
                game.revealAllMines()
                invalidate()
                listener?.onGameLost()
            }
            RevealResult.WON -> {
                game.flagAllMines()
                invalidate()
                listener?.onGameWon()
            }
            RevealResult.OK -> {
                invalidate()
                listener?.onReveal()
            }
            // Nothing changed (flagged cell, chord that does not apply): stay
            // silent so the feedback is not misleading.
            RevealResult.IGNORED -> Unit
        }
    }

    private fun performFlag(col: Int, row: Int) {
        if (game.isOver) return
        if (game.toggleFlag(col, row)) {
            invalidate()
            listener?.onFlagChange()
        }
    }

    // --- Geometry ---------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateBoardMetrics()
    }

    private fun updateBoardMetrics() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || game.cols <= 0 || game.rows <= 0) return
        cellSize = min(w / game.cols, h / game.rows)
        boardLeft = (w - cellSize * game.cols) / 2f
        boardTop = (h - cellSize * game.rows) / 2f
    }

    private fun cellAtPosition(x: Float, y: Float): Pair<Int, Int>? {
        if (cellSize <= 0f) return null
        val c = ((x - boardLeft) / cellSize).toInt()
        val r = ((y - boardTop) / cellSize).toInt()
        return if (c in 0 until game.cols && r in 0 until game.rows) c to r else null
    }

    // --- Rendering --------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(COLOR_BOARD_BG)

        if (cellSize <= 0f) return

        for (r in 0 until game.rows) {
            for (c in 0 until game.cols) {
                val x = boardLeft + c * cellSize
                val y = boardTop + r * cellSize
                drawCell(canvas, game.cellAt(c, r)!!, x, y, cellSize)
            }
        }

        if (hasFocus()) {
            val x = boardLeft + cursorCol * cellSize
            val y = boardTop + cursorRow * cellSize
            cursorPaint.strokeWidth = cellSize * 0.08f
            canvas.drawRect(
                x + cellSize * 0.04f, y + cellSize * 0.04f,
                x + cellSize * 0.96f, y + cellSize * 0.96f,
                cursorPaint
            )
        }
    }

    private fun drawCell(canvas: Canvas, cell: Cell, x: Float, y: Float, s: Float) {
        val cx = x + s / 2f
        val cy = y + s / 2f

        if (cell.isExploded) {
            canvas.drawRect(x, y, x + s, y + s, explodedPaint)
            drawMine(canvas, cx, cy, s)
            return
        }

        if (cell.isRevealed) {
            canvas.drawRect(x, y, x + s, y + s, flatPaint)
            canvas.drawRect(x, y, x + s, y + s, flatBorderPaint)
            when {
                cell.isMine -> drawMine(canvas, cx, cy, s)
                cell.adjacentMines > 0 -> {
                    textPaint.color = NUMBER_COLORS[cell.adjacentMines - 1]
                    textPaint.textSize = s * 0.62f
                    drawCenteredText(canvas, cell.adjacentMines.toString(), cx, cy, s)
                }
            }
            return
        }

        // Unrevealed: raised bevel.
        canvas.drawRect(x, y, x + s, y + s, raisedPaint)
        val bevel = s * 0.1f
        canvas.drawRect(x, y, x + s, y + bevel, lightPaint)
        canvas.drawRect(x, y, x + bevel, y + s, lightPaint)
        canvas.drawRect(x + s - bevel, y, x + s, y + s, darkPaint)
        canvas.drawRect(x, y + s - bevel, x + s, y + s, darkPaint)

        if (cell.isFlagged) {
            drawFlag(canvas, cx, cy, s)
        }
        if (cell.isWrongFlag) {
            drawWrongFlag(canvas, cx, cy, s)
        }
    }

    private fun drawCenteredText(canvas: Canvas, text: String, cx: Float, cy: Float, s: Float) {
        val fm = textPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, cx, baseline, textPaint)
    }

    private fun drawMine(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val r = s * 0.26f
        canvas.drawCircle(cx, cy, r, minePaint)
        spikePaint.strokeWidth = s * 0.08f
        for (i in 0 until 8) {
            val angle = Math.toRadians(i * 45.0)
            val cos = Math.cos(angle).toFloat()
            val sin = Math.sin(angle).toFloat()
            canvas.drawLine(
                cx + cos * s * 0.32f, cy + sin * s * 0.32f,
                cx + cos * s * 0.44f, cy + sin * s * 0.44f,
                spikePaint
            )
        }
    }

    private fun drawFlag(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        polePaint.strokeWidth = s * 0.08f
        canvas.drawLine(cx - s * 0.2f, cy - s * 0.28f, cx - s * 0.2f, cy + s * 0.26f, polePaint)
        flagPath.reset()
        flagPath.moveTo(cx - s * 0.14f, cy - s * 0.24f)
        flagPath.lineTo(cx + s * 0.28f, cy - s * 0.06f)
        flagPath.lineTo(cx - s * 0.14f, cy + s * 0.12f)
        flagPath.close()
        canvas.drawPath(flagPath, flagPaint)
    }

    private fun drawWrongFlag(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        wrongFlagPaint.strokeWidth = s * 0.09f
        val r = s * 0.22f
        canvas.drawLine(cx - r, cy - r, cx + r, cy + r, wrongFlagPaint)
        canvas.drawLine(cx + r, cy - r, cx - r, cy + r, wrongFlagPaint)
    }

    companion object {
        private const val COLOR_BOARD_BG = 0xFF2B2B2B.toInt()
        private const val COLOR_RAISED = 0xFFC0C0C0.toInt()
        private const val COLOR_LIGHT = 0xFFFFFFFF.toInt()
        private const val COLOR_DARK = 0xFF808080.toInt()
        private const val COLOR_FLAT = 0xFFBDBDBD.toInt()
        private const val COLOR_FLAT_BORDER = 0xFF9E9E9E.toInt()
        private const val COLOR_EXPLODED = 0xFFE53935.toInt()
        private const val COLOR_WRONG_FLAG = 0xFFD32F2F.toInt()
        private const val COLOR_CURSOR = 0xFFFFD600.toInt()
        private const val COLOR_MINE = 0xFF101010.toInt()
        private const val COLOR_POLE = 0xFF5D4037.toInt()
        private const val COLOR_FLAG = 0xFFE53935.toInt()

        /** Delay between cursor moves while a gamepad direction is held. */
        private const val GAMEPAD_REPEAT_MS = 110L

        /** Window in which hat-axis input is ignored because DPAD_* keys are arriving. */
        private const val GAMEPAD_KEY_DEDUPE_MS = 120L

        private val NUMBER_COLORS = intArrayOf(
            0xFF0000FF.toInt(), // 1
            0xFF008000.toInt(), // 2
            0xFFE53935.toInt(), // 3
            0xFF000080.toInt(), // 4
            0xFF800000.toInt(), // 5
            0xFF008080.toInt(), // 6
            0xFF000000.toInt(), // 7
            0xFF616161.toInt()  // 8
        )
    }
}
