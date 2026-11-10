package org.brain.capture.transcribe

import kotlin.math.sqrt

/**
 * Splits long audio into pieces Whisper can handle (≤ 28 s), cutting at the quietest 100 ms
 * in the last few seconds of each window so words are not cut in half.
 * Phase 2 replaces this with real voice-activity detection.
 */
object Chunker {
    private const val RATE = WhisperOnnx.SAMPLE_RATE
    private const val MAX = 28 * RATE
    private const val SEARCH = 4 * RATE
    private const val FRAME = RATE / 10

    fun split(samples: FloatArray): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        var start = 0
        while (samples.size - start > MAX) {
            val from = start + MAX - SEARCH
            var cut = start + MAX
            var quietest = Double.MAX_VALUE
            var f = from
            while (f + FRAME <= start + MAX) {
                val e = rms(samples, f, FRAME)
                if (e < quietest) { quietest = e; cut = f + FRAME / 2 }
                f += FRAME / 2
            }
            out += samples.copyOfRange(start, cut)
            start = cut
        }
        if (samples.size - start > RATE / 4) out += samples.copyOfRange(start, samples.size)
        return out
    }

    private fun rms(s: FloatArray, from: Int, len: Int): Double {
        var sum = 0.0
        for (i in from until from + len) sum += s[i] * s[i]
        return sqrt(sum / len)
    }
}
