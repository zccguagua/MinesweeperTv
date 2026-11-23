package com.zcc.minesweeper

import android.annotation.TargetApi
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build

/**
 * Short sound effects for revealing, flagging, winning and losing.
 *
 * The clips are tiny WAVs in res/raw loaded into a SoundPool, which keeps the
 * latency low and lets several effects overlap. Loading is asynchronous, so
 * sounds requested before the pool is ready are dropped rather than queued.
 */
class SoundPlayer(context: Context) {

    private val pool: SoundPool = createPool()

    private val loadedIds = HashSet<Int>(8)

    private val revealId: Int
    private val flagId: Int
    private val winId: Int
    private val loseId: Int

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loadedIds.add(sampleId)
        }
        revealId = load(context, R.raw.sfx_reveal)
        flagId = load(context, R.raw.sfx_flag)
        winId = load(context, R.raw.sfx_win)
        loseId = load(context, R.raw.sfx_lose)
    }

    private fun load(context: Context, resId: Int): Int = pool.load(context, resId, 1)

    fun playReveal() = play(revealId, VOLUME_REVEAL)

    fun playFlag() = play(flagId, VOLUME_FLAG)

    fun playWin() = play(winId, VOLUME_RESULT)

    fun playLose() = play(loseId, VOLUME_RESULT)

    private fun play(sampleId: Int, volume: Float) {
        if (sampleId == 0 || !loadedIds.contains(sampleId)) return
        pool.play(sampleId, volume, volume, PRIORITY, 0, 1f)
    }

    fun release() {
        loadedIds.clear()
        pool.release()
    }

    /** SoundPool.Builder needs API 21, so API 19 keeps using the old constructor. */
    private fun createPool(): SoundPool =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Lollipop.newPool()
        } else {
            @Suppress("DEPRECATION")
            SoundPool(MAX_STREAMS, AudioManager.STREAM_MUSIC, 0)
        }

    /**
     * Isolated in a nested object so that `SoundPool.Builder` / `AudioAttributes`
     * are only resolved (and the class only loaded) on API 21 and newer.
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private object Lollipop {
        fun newPool(): SoundPool = SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
    }

    private companion object {
        const val MAX_STREAMS = 4
        const val PRIORITY = 1
        const val VOLUME_REVEAL = 0.45f
        const val VOLUME_FLAG = 0.55f
        const val VOLUME_RESULT = 0.85f
    }
}
