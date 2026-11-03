package com.example.minesweeper

/**
 * A single cell on the board.
 */
class Cell {
    var isMine: Boolean = false
    var isRevealed: Boolean = false
    var isFlagged: Boolean = false
    var adjacentMines: Int = 0

    /** The mine that was clicked and caused the loss. */
    var isExploded: Boolean = false

    /** A flag that turned out to be placed on a safe cell after a loss. */
    var isWrongFlag: Boolean = false
}
