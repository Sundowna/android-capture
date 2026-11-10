package org.brain.capture.transcribe

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.extensions.OrtxPackage
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import kotlin.math.ceil

/**
 * Whisper-small int8 (ONNX, with KV cache) as used by RTranslator and Whisper+.
 * Written from the pipeline description in docs/whisper-onnx-pipeline.md; no code copied from
 * Whisper+ (GPL). One call to [transcribe] handles at most ~30 s of audio.
 */
class WhisperOnnx(modelDir: File) : Closeable {

    private val env = OrtEnvironment.getEnvironment()
    private val initializer: OrtSession
    private val encoder: OrtSession
    private val cacheInit: OrtSession
    private val decoder: OrtSession
    private val detokenizer: OrtSession

    init {
        fun path(name: String) = File(modelDir, name).also {
            require(it.exists()) { "Model file missing: $name" }
        }.path

        initializer = env.createSession(path(INITIALIZER), lowMemOptions())
        encoder = env.createSession(path(ENCODER), OrtSession.SessionOptions().apply {
            registerCustomOpLibrary(OrtxPackage.getLibraryPath())
            // The Pixel 7 Pro has 12 GB RAM, so use the faster (non-reduced-RAM) encoder settings.
            setCPUArenaAllocator(true)
            setMemoryPatternOptimization(true)
            setSymbolicDimensionValue("batch_size", 1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
        })
        cacheInit = env.createSession(path(CACHE_INIT), lowMemOptions())
        decoder = env.createSession(path(DECODER), lowMemOptions())
        detokenizer = env.createSession(path(DETOKENIZER), lowMemOptions(optimize = true))
    }

    private fun lowMemOptions(optimize: Boolean = false) = OrtSession.SessionOptions().apply {
        registerCustomOpLibrary(OrtxPackage.getLibraryPath())
        setCPUArenaAllocator(false)
        setMemoryPatternOptimization(false)
        if (!optimize) setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
    }

    /**
     * @param samples 16 kHz mono PCM in [-1, 1], at most ~30 s.
     * @param language Whisper language code ("de", "en", …) or null to let the model detect it.
     */
    fun transcribe(samples: FloatArray, language: String?): String {
        if (samples.size < SAMPLE_RATE / 4) return "" // under 0.25 s: nothing to transcribe
        val maxTokens = minOf(MAX_TOKENS, ceil(samples.size / SAMPLE_RATE.toDouble() * 30).toInt())

        OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())).use { pcm ->
            initializer.run(mapOf("audio_pcm" to pcm)).use { features ->
                encoder.run(mapOf("input_features" to features[0] as OnnxTensor)).use { hidden ->
                    cacheInit.run(mapOf("encoder_hidden_states" to hidden[0] as OnnxTensor)).use { crossKv ->
                        val encoderKv = HashMap<String, OnnxTensor>()
                        for (i in 0 until LAYERS) for (kv in KV) {
                            encoderKv["past_key_values.$i.encoder.$kv"] =
                                crossKv.get("present.$i.encoder.$kv").get() as OnnxTensor
                        }
                        val tokens = decode(encoderKv, languageToken(language), maxTokens)
                        return clean(detokenize(tokens))
                    }
                }
            }
        }
    }

    private fun decode(encoderKv: Map<String, OnnxTensor>, langToken: Int?, maxTokens: Int): IntArray {
        val forced = ArrayDeque<Int>()
        langToken?.let(forced::add)
        forced += TRANSCRIBE
        forced += NO_TIMESTAMPS
        var detectLanguage = langToken == null

        val empty = HashMap<String, OnnxTensor>()
        for (i in 0 until LAYERS) for (kv in KV) {
            empty["past_key_values.$i.decoder.$kv"] =
                OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, HEADS, 0, HEAD_DIM))
        }
        var past: Map<String, OnnxTensor> = empty
        var previous: OrtSession.Result? = null
        val out = ArrayList<Int>()
        var next = SOT

        try {
            while (true) {
                val ids = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(next.toLong())), longArrayOf(1, 1))
                val inputs = HashMap<String, OnnxTensor>(past.size + encoderKv.size + 1)
                inputs["input_ids"] = ids
                inputs.putAll(past)
                inputs.putAll(encoderKv)
                val result = decoder.run(inputs)
                ids.close()
                // The previous step's cache was only needed as input to this step.
                previous?.close() ?: empty.values.forEach(OnnxValue::close)
                previous = result

                val logits = (result.get("logits").get() as OnnxTensor).floatBuffer
                val best = argmax(logits)
                past = buildMap {
                    for (i in 0 until LAYERS) for (kv in KV) {
                        put("past_key_values.$i.decoder.$kv", result.get("present.$i.decoder.$kv").get() as OnnxTensor)
                    }
                }

                next = when {
                    detectLanguage -> {
                        detectLanguage = false
                        if (best in FIRST_LANGUAGE..LAST_LANGUAGE) best else FIRST_LANGUAGE // fall back to English
                    }
                    forced.isNotEmpty() -> forced.removeFirst()
                    best == EOT || out.size >= maxTokens -> break
                    else -> { out += best; best }
                }
            }
        } finally {
            previous?.close()
        }
        return out.toIntArray()
    }

    private fun detokenize(tokens: IntArray): String {
        if (tokens.isEmpty()) return ""
        OnnxTensor.createTensor(env, IntBuffer.wrap(tokens), longArrayOf(1, 1, tokens.size.toLong())).use { seq ->
            detokenizer.run(mapOf("sequences" to seq)).use { r ->
                val v = r[0].value
                return when (v) {
                    is Array<*> -> ((v[0] as? Array<*>)?.get(0) ?: v[0]).toString()
                    else -> v.toString()
                }
            }
        }
    }

    private fun clean(text: String): String =
        text.replace(Regex("<\\|[^>]*\\|>\\s?"), "").trim().replaceFirstChar { it.uppercaseChar() }

    private fun argmax(b: FloatBuffer): Int {
        var best = 0
        var max = Float.NEGATIVE_INFINITY
        for (i in 0 until b.limit()) { val v = b.get(i); if (v > max) { max = v; best = i } }
        return best
    }

    override fun close() {
        listOf(initializer, encoder, cacheInit, decoder, detokenizer).forEach { runCatching { it.close() } }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val INITIALIZER = "Whisper_initializer.onnx"
        const val ENCODER = "Whisper_encoder.onnx"
        const val CACHE_INIT = "Whisper_cache_initializer.onnx"
        const val DECODER = "Whisper_decoder.onnx"
        const val DETOKENIZER = "Whisper_detokenizer.onnx"
        val REQUIRED = listOf(INITIALIZER, ENCODER, CACHE_INIT, DECODER, DETOKENIZER)

        private const val LAYERS = 12
        private const val HEADS = 12L
        private const val HEAD_DIM = 64L
        private val KV = arrayOf("key", "value")
        private const val MAX_TOKENS = 445
        private const val EOT = 50257
        private const val SOT = 50258
        private const val FIRST_LANGUAGE = 50259 // "en"
        private const val LAST_LANGUAGE = 50357
        private const val TRANSCRIBE = 50359
        private const val NO_TIMESTAMPS = 50363

        /** Whisper's language order; token id = SOT + 1 + index. */
        private val LANGUAGES = listOf(
            "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr", "pl", "ca", "nl", "ar", "sv", "it",
            "id", "hi", "fi", "vi", "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta", "no",
        )

        fun languageToken(code: String?): Int? {
            val idx = LANGUAGES.indexOf(code)
            return if (idx < 0) null else SOT + 1 + idx
        }
    }
}
