package com.example.minesweeper

import kotlin.random.Random

/** Outcome of a [MinesweeperGame.reveal] call. */
enum class RevealResult {
    /** Something was revealed. */
    OK,
    WON,
    LOST,

    /** Nothing happened, e.g. a flagged cell or a chord that does not apply. */
    IGNORED
}

/**
 * Pure game logic with no Android dependencies.
 *
 * Mines are generated lazily on the first reveal so the first cell (and its
 * eight neighbours) are guaranteed safe, matching modern Minesweeper behaviour.
 */
class MinesweeperGame(
    val cols: Int,
    val rows: Int,
    val mineCount: Int
) {
    val cells: Array<Array<Cell>> = Array(rows) { Array(cols) { Cell() } }

    var isOver = false
        private set
    var isWon = false
        private set

    /** Whether mines have been placed (i.e. the first reveal happened). */
    var isStarted = false
        private set

    private var revealedCount = 0
    private var flagCount = 0

    fun cellAt(col: Int, row: Int): Cell? =
        if (col in 0 until cols && row in 0 until rows) cells[row][col] else null

    fun minesRemaining(): Int = (mineCount - flagCount).coerceAtLeast(0)

    /**
     * Reveal a cell. On a revealed number this also performs the classic
     * "chord" action when the surrounding flag count matches the number.
     */
    fun reveal(col: Int, row: Int): RevealResult {
        if (isOver) return RevealResult.IGNORED
        val cell = cellAt(col, row) ?: return RevealResult.IGNORED
        if (cell.isFlagged) return RevealResult.IGNORED

        if (!isStarted) {
            generateMines(col, row)
            isStarted = true
        }

        if (cell.isRevealed) {
            return chord(col, row)
        }
        if (cell.isMine) {
            cell.isExploded = true
            return lose()
        }

        floodReveal(col, row)
        return finishCheck()
    }

    /**
     * Toggle a flag. Returns true if the board changed. Like the original game,
     * no more flags than mines can be placed.
     */
    fun toggleFlag(col: Int, row: Int): Boolean {
        if (isOver) return false
        val cell = cellAt(col, row) ?: return false
        if (cell.isRevealed) return false
        if (!cell.isFlagged && flagCount >= mineCount) return false
        cell.isFlagged = !cell.isFlagged
        flagCount += if (cell.isFlagged) 1 else -1
        return true
    }

    /** Reveal every mine and mark misplaced flags, used after a loss. */
    fun revealAllMines() {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cell = cells[r][c]
                if (cell.isMine) {
                    cell.isRevealed = true
                } else if (cell.isFlagged) {
                    cell.isWrongFlag = true
                }
            }
        }
    }

    /** Flag every remaining mine, used on a win. */
    fun flagAllMines() {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cell = cells[r][c]
                if (cell.isMine && !cell.isFlagged) {
                    cell.isFlagged = true
                    flagCount++
                }
            }
        }
    }

    private fun lose(): RevealResult {
        isOver = true
        isWon = false
        return RevealResult.LOST
    }

    private fun finishCheck(): RevealResult {
        if (revealedCount == cols * rows - mineCount) {
            isWon = true
            isOver = true
            return RevealResult.WON
        }
        return RevealResult.OK
    }

    private fun generateMines(firstCol: Int, firstRow: Int) {
        val excluded = HashSet<Int>()
        for (dr in -1..1) {
            for (dc in -1..1) {
                val c = firstCol + dc
                val r = firstRow + dr
                if (c in 0 until cols && r in 0 until rows) {
                    excluded.add(r * cols + c)
                }
            }
        }

        var placed = 0
        while (placed < mineCount) {
            val idx = Random.nextInt(cols * rows)
            if (idx in excluded) continue
            val r = idx / cols
            val c = idx % cols
            if (cells[r][c].isMine) continue
            cells[r][c].isMine = true
            placed++
        }

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (!cells[r][c].isMine) {
                    cells[r][c].adjacentMines = countAdjacentMines(c, r)
                }
            }
        }
    }

    private fun countAdjacentMines(col: Int, row: Int): Int {
        var count = 0
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val c = col + dc
                val r = row + dr
                if (c in 0 until cols && r in 0 until rows && cells[r][c].isMine) count++
            }
        }
        return count
    }

    private fun floodReveal(col: Int, row: Int) {
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(col, row))
        while (stack.isNotEmpty()) {
            val (c, r) = stack.removeLast()
            val cell = cellAt(c, r) ?: continue
            if (cell.isRevealed || cell.isFlagged || cell.isMine) continue
            cell.isRevealed = true
            revealedCount++
            if (cell.adjacentMines != 0) continue
            for (dr in -1..1) {
                for (dc in -1..1) {
                    if (dr == 0 && dc == 0) continue
                    val nc = c + dc
                    val nr = r + dr
                    val neighbour = cellAt(nc, nr) ?: continue
                    if (!neighbour.isRevealed && !neighbour.isFlagged && !neighbour.isMine) {
                        stack.addLast(intArrayOf(nc, nr))
                    }
                }
            }
        }
    }

    private fun chord(col: Int, row: Int): RevealResult {
        val cell = cellAt(col, row) ?: return RevealResult.IGNORED
        if (!cell.isRevealed || cell.adjacentMines == 0) return RevealResult.IGNORED

        val neighbours = mutableListOf<IntArray>()
        var flags = 0
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val c = col + dc
                val r = row + dr
                val n = cellAt(c, r) ?: continue
                neighbours.add(intArrayOf(c, r))
                if (n.isFlagged) flags++
            }
        }

        if (flags != cell.adjacentMines) return RevealResult.IGNORED

        // A wrongly-placed flag means the chord is invalid and the player loses.
        for ((c, r) in neighbours) {
            val n = cellAt(c, r)!!
            if (n.isFlagged && !n.isMine) {
                n.isWrongFlag = true
                return lose()
            }
        }

        for ((c, r) in neighbours) {
            val n = cellAt(c, r)!!
            if (n.isFlagged || n.isRevealed) continue
            if (n.isMine) {
                n.isExploded = true
                return lose()
            }
            floodReveal(c, r)
        }

        return finishCheck()
    }
}
