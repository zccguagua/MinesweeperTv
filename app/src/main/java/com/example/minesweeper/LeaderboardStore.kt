package com.example.minesweeper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A single leaderboard entry. */
data class ScoreEntry(val seconds: Int, val dateMillis: Long)

/**
 * Persists the best completion times per difficulty using SharedPreferences.
 */
class LeaderboardStore(context: Context) {

    private val prefs = context.getSharedPreferences("leaderboard", Context.MODE_PRIVATE)

    fun getEntries(difficulty: Difficulty): List<ScoreEntry> {
        val json = prefs.getString(difficulty.name, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ScoreEntry(obj.getInt("s"), obj.getLong("d"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Adds a score and returns its 1-based rank in the top list, or -1 if it
     * did not make the list.
     */
    fun addEntry(difficulty: Difficulty, seconds: Int): Int {
        val now = System.currentTimeMillis()
        val list = getEntries(difficulty).toMutableList()
        list.add(ScoreEntry(seconds, now))
        list.sortWith(compareBy({ it.seconds }, { it.dateMillis }))
        val trimmed = list.take(MAX_ENTRIES)

        val arr = JSONArray()
        trimmed.forEach {
            arr.put(JSONObject().put("s", it.seconds).put("d", it.dateMillis))
        }
        prefs.edit().putString(difficulty.name, arr.toString()).apply()

        val index = trimmed.indexOfFirst { it.seconds == seconds && it.dateMillis == now }
        return if (index >= 0) index + 1 else -1
    }

    private companion object {
        const val MAX_ENTRIES = 10
    }
}
