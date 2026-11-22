package com.neuralml.terminal.core

import android.os.SystemClock
import kotlin.math.min

/** One highlighted neuro-feature point drawn on top of the waveform. */
data class FeatureMark(
    /** Channel index 0..3. */
    val channel: Int,
    /** Horizontal position relative to the newest sample: 0 = oldest visible, 1 = newest. */
    val position: Float,
    /** 0..1, controls glow radius and alpha. */
    val strength: Float,
    /** `SystemClock.uptimeMillis()` when the mark was created. */
    val bornMs: Long,
)

/**
 * Four ring buffers of waveform samples plus a small list of feature marks.
 *
 * Only touched from the main thread (engine appends, views read).
 */
class WaveData(val capacity: Int = 1024) {

    companion object {
        const val CHANNELS = 4
        const val MARK_LIFETIME_MS = 1200L
    }

    private val data = Array(CHANNELS) { FloatArray(capacity) }
    private var totalSamples = 0L

    /** Feature marks newer than [MARK_LIFETIME_MS] are kept. */
    private val markList = ArrayList<FeatureMark>(48)
    private val markLock = Any()

    /**
     * Append one sample per channel (`values.size` should be [CHANNELS];
     * missing entries are treated as 0).
     */
    fun append(values: FloatArray) {
        val index = (totalSamples % capacity).toInt()
        for (ch in 0 until CHANNELS) {
            data[ch][index] = if (ch < values.size) values[ch].coerceIn(-1f, 1f) else 0f
        }
        totalSamples++
    }

    /** Number of samples currently readable per channel (at most [capacity]). */
    fun count(): Int = min(totalSamples, capacity.toLong()).toInt()

    /**
     * Sample for [channel] where `i == 0` is the oldest readable sample and
     * `i == count() - 1` is the newest one.
     */
    fun at(channel: Int, i: Int): Float {
        val n = count()
        if (n == 0 || i < 0 || i >= n) return 0f
        val start = totalSamples - n
        val absolute = start + i
        return data[channel][(absolute % capacity).toInt()]
    }

    /** Newest sample of a channel. */
    fun latest(channel: Int): Float {
        val n = count()
        return if (n == 0) 0f else at(channel, n - 1)
    }

    fun addMark(channel: Int, position: Float, strength: Float, nowMs: Long) {
        synchronized(markLock) {
            if (markList.size >= 48) markList.removeAt(0)
            markList.add(
                FeatureMark(
                    channel = channel.coerceIn(0, CHANNELS - 1),
                    position = position.coerceIn(0f, 1f),
                    strength = strength.coerceIn(0f, 1f),
                    bornMs = nowMs,
                )
            )
        }
    }

    /** Copy of the live marks, oldest first. Marks older than [MARK_LIFETIME_MS] are dropped. */
    fun marks(nowMs: Long = SystemClock.uptimeMillis()): List<FeatureMark> {
        synchronized(markLock) {
            markList.removeAll { nowMs - it.bornMs > MARK_LIFETIME_MS }
            return ArrayList(markList)
        }
    }

    fun clearMarks() {
        synchronized(markLock) { markList.clear() }
    }
}
