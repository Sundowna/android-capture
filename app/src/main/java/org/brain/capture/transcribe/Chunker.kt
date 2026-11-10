package org.brain.capture.transcribe

import kotlin.math.sqrt

/**
 * Splits long audio into pieces Whisper can handle (≤ 28 s each), cutting in the middle of the
 * longest pause near the end of each window so words and sentences stay whole.
 *
 * A "pause" is a run of 30 ms frames quieter than the recording's own background level,
 * so it adapts to quiet rooms and noisy streets alike.
 * If a window has no pause, it is cut at its quietest moment.
 */
object Chunker {
    private const val RATE = WhisperOnnx.SAMPLE_RATE
    private const val MAX = 28 * RATE
    private const val SEARCH = 8 * RATE
    private const val FRAME = RATE * 30 / 1000
    private const val MIN_PAUSE_FRAMES = 6 // 180 ms

    /** Frame length (samples) the energy profile must use. */
    const val FRAME_SAMPLES = FRAME

    fun split(samples: FloatArray): List<FloatArray> {
        val energy = DoubleArray(samples.size / FRAME) { rms(samples, it * FRAME, FRAME) }
        return plan(energy, samples.size.toLong()).map { samples.copyOfRange(it.first.toInt(), it.last.toInt() + 1) }
    }

    /**
     * Sample ranges for the pieces of a recording of [total] samples, from its 30 ms loudness
     * profile. Lets long recordings be transcribed piece by piece without loading them whole.
     */
    fun plan(energy: DoubleArray, total: Long): List<LongRange> {
        if (total <= MAX) return if (total > RATE / 4) listOf(0L until total) else emptyList()
        // Background level: twice the quietest tenth, but never above a third of the typical
        // loudness (a recording that is nearly all speech has no quiet tenth to measure).
        val threshold = maxOf(0.002, minOf(2 * percentile(energy, 0.1), 0.3 * percentile(energy, 0.5)))
        val out = ArrayList<LongRange>()
        var start = 0L
        while (total - start > MAX) {
            val cut = cutPoint(energy, threshold, (start + MAX - SEARCH).toInt(), (start + MAX).toInt()).toLong()
            out += start until cut
            start = cut
        }
        if (total - start > RATE / 4) out += start until total
        return out
    }

    /** Sample index to cut at, between [from] and [to]. */
    internal fun cutPoint(energy: DoubleArray, threshold: Double, from: Int, to: Int): Int {
        val f0 = from / FRAME
        val f1 = minOf(to / FRAME, energy.size)
        var bestStart = -1; var bestLen = 0
        var runStart = -1
        for (f in f0 until f1) {
            if (energy[f] < threshold) {
                if (runStart < 0) runStart = f
                val len = f - runStart + 1
                // Prefer longer pauses; on a tie, the later one (keeps pieces long).
                if (len >= bestLen) { bestLen = len; bestStart = runStart }
            } else runStart = -1
        }
        if (bestLen >= MIN_PAUSE_FRAMES) return (bestStart + bestLen / 2) * FRAME + FRAME / 2
        // No real pause: the quietest frame.
        var q = f1 - 1
        for (f in f0 until f1) if (energy[f] < energy[q]) q = f
        return q * FRAME + FRAME / 2
    }

    private fun percentile(v: DoubleArray, p: Double): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sortedArray()
        return s[((s.size - 1) * p).toInt()]
    }

    private fun rms(s: FloatArray, from: Int, len: Int): Double {
        var sum = 0.0
        for (i in from until from + len) sum += s[i] * s[i]
        return sqrt(sum / len)
    }
}
