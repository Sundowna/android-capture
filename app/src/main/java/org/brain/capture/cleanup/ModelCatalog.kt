package org.brain.capture.cleanup

/**
 * The AI cleanup models Settings offers to download (in the browser; the app never goes online),
 * and what suits this phone. Only models tested with this app's instructions are listed: smaller
 * ones (Qwen 2.5 1.5B, Qwen 3 0.6B) loaded on a Pixel 6a but ignored the source-number format, so
 * every answer was rejected (tested 2026-11-26).
 */
object ModelCatalog {
    data class Model(
        val id: String,
        val name: String,
        /** Download size as shown, e.g. "2.6 GB". */
        val size: String,
        val url: String,
        /** One line on what to expect. */
        val note: String,
    )

    const val GEMMA_E2B = "gemma-4-e2b"

    /** Below this (in GB, as people know it) no model is advised: notes keep the exact words. */
    const val MIN_MEMORY_GB = 6

    val MODELS = listOf(
        Model(GEMMA_E2B, "Gemma 4 E2B", "2.6 GB",
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true",
            "From Google, free, no account needed."),
    )

    /** Phone memory as people know it ("6 GB"), from the total Android reports (a bit less). */
    fun memoryGb(totalBytes: Long): Int = Math.round(totalBytes / 1e9).toInt()

    /** The model for a phone with [memoryGb] GB, or null when no AI model is advised (use Exact words). */
    fun recommended(memoryGb: Int): Model? =
        if (memoryGb >= MIN_MEMORY_GB) MODELS.first { it.id == GEMMA_E2B } else null

    /** The short advice for this phone, e.g. "Pixel 6a, 6 GB memory: Gemma 4 E2B runs well; …". */
    fun advice(phone: String, memoryGb: Int): String {
        val head = "$phone, $memoryGb GB memory: "
        return when {
            memoryGb >= 8 -> head + "Gemma 4 E2B runs well here."
            memoryGb >= MIN_MEMORY_GB -> head + "Gemma 4 E2B runs well; the first note after a while waits about 30 s while it loads."
            else -> head + "too little memory for the AI model. Notes keep your exact words, which works fine."
        }
    }
}
