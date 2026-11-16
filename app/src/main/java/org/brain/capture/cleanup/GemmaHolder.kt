package org.brain.capture.cleanup

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Keeps one Gemma engine. It is loaded when a recording starts (warm-load) or on first use, and
 * unloaded after [IDLE_MS] without work, so quick follow-up memos need no reload but the ~3 GB
 * are not held all day. Runs fully on the phone; the app has no internet permission.
 */
object GemmaHolder {
    private const val TAG = "Gemma"
    private const val IDLE_MS = 10 * 60 * 1000L
    private const val WARM_DELAY_MS = 1500L

    /**
     * Runs [block] at background priority (threads the model starts inherit it), so the screen
     * always gets the processor first; the thread's own priority is restored afterwards.
     */
    fun <T> background(block: () -> T): T {
        val tid = android.os.Process.myTid()
        val before = android.os.Process.getThreadPriority(tid)
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        try { return block() } finally { android.os.Process.setThreadPriority(before) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var engine: Engine? = null
    private var idleJob: Job? = null

    /**
     * The model file, in app-private storage: files/gemma/ (any .litertlm file, E2B before E4B by
     * name). For now it is copied in by hand with adb (run-as); an import screen comes later.
     */
    fun modelFile(ctx: Context): File? =
        File(ctx.filesDir, "gemma").listFiles { f -> f.name.endsWith(".litertlm") && f.length() > 0 }
            ?.sortedBy { it.name }?.firstOrNull()

    fun modelName(ctx: Context): String = modelFile(ctx)?.name?.removeSuffix(".litertlm") ?: "gemma"

    /** Starts loading in the background if a model is installed. */
    fun warm(ctx: Context) {
        if (modelFile(ctx) == null) return
        scope.launch {
            // Let the capture screen finish sliding in first: the GPU load briefly stalls drawing.
            delay(WARM_DELAY_MS)
            runCatching { lock.withLock { background { ensureLoaded(ctx.applicationContext) } } }
                .onFailure { Log.w(TAG, "Warm-load failed", it) }
        }
    }

    /** One prompt, one answer, in a fresh conversation (no history carried between notes). */
    suspend fun generate(ctx: Context, prompt: String): String = lock.withLock {
        val e = background { ensureLoaded(ctx.applicationContext) }
        idleJob?.cancel()
        try {
            e.createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                    extraContext = mapOf("enable_thinking" to false),
                )
            ).use { c -> background { c.sendMessage(prompt).toString().trim() } }
        } finally {
            scheduleUnload()
        }
    }

    /** Caller holds [lock]. */
    private fun ensureLoaded(ctx: Context): Engine {
        engine?.let { scheduleUnload(); return it }
        val file = modelFile(ctx) ?: error("No Gemma model installed")
        val cache = File(ctx.cacheDir, "gemma").apply { mkdirs() }.path
        val t0 = SystemClock.elapsedRealtime()
        fun open(b: Backend) = Engine(EngineConfig(modelPath = file.path, backend = b, cacheDir = cache)).also { it.initialize() }
        val e = try {
            open(Backend.GPU())
        } catch (t: Throwable) {
            Log.w(TAG, "GPU load failed, using CPU", t)
            open(Backend.CPU())
        }
        Log.i(TAG, "Loaded ${file.name} in ${SystemClock.elapsedRealtime() - t0} ms")
        engine = e
        scheduleUnload()
        return e
    }

    private fun scheduleUnload() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_MS)
            lock.withLock {
                engine?.close()
                engine = null
                Log.i(TAG, "Unloaded after ${IDLE_MS / 60000} idle minutes")
            }
        }
    }
}
