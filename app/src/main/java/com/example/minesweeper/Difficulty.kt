package com.example.minesweeper

/**
 * The three difficulty levels available in the game.
 *
 * Sizes are chosen to stay comfortable on a 1080p TV screen while still
 * ramping up the challenge. "疯狂" keeps the classic ~19% mine density of the
 * original Windows expert board.
 */
enum class Difficulty(
    val displayName: String,
    val cols: Int,
    val rows: Int,
    val mineCount: Int
) {
    NORMAL("普通", 9, 9, 10),
    HARD("困难", 16, 16, 40),
    CRAZY("疯狂", 20, 16, 60);

    companion object {
        fun fromName(name: String?): Difficulty =
            entries.firstOrNull { it.name == name } ?: NORMAL
    }
}
