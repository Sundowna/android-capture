package org.brain.capture.transcribe

object LoopGuard {
    /**
     * Whisper sometimes gets stuck repeating the same few tokens (mostly on noise or silence).
     * Stop when the last 1–8 tokens have repeated 4 times in a row; the repeats are dropped.
     */
    fun isLooping(out: ArrayList<Int>): Boolean {
        for (len in 1..8) {
            val need = len * 4
            if (out.size < need) break
            val tail = out.subList(out.size - len, out.size)
            var repeats = true
            for (r in 1 until 4) {
                if (out.subList(out.size - len * (r + 1), out.size - len * r) != tail) { repeats = false; break }
            }
            if (repeats) {
                repeat(len * 3) { out.removeAt(out.size - 1) }
                return true
            }
        }
        return false
    }
}
