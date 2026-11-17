package org.brain.capture.cleanup

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Debug-only test hook, no vault needed:
 * adb shell am start -n org.brain.capture.play/org.brain.capture.cleanup.CleanupProbeActivity --es text "…" [--es type c-fixfeature]
 * then read `adb logcat -s CleanupProbe Gemma`.
 */
class CleanupProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra("text").orEmpty()
        val ctx = applicationContext
        // Stay open until done: a finished activity leaves a cached process, which Android freezes.
        scope.launch {
            runCatching {
                Log.i(TAG, "Model: ${GemmaHolder.modelFile(ctx)}")
                val segs = CleanupText.segments(text)
                val t0 = SystemClock.elapsedRealtime()
                // --es type c-fixfeature: that capture type's instruction (default Idea).
                val p = org.brain.capture.types.Profiles.get(ctx, intent.getStringExtra("type") ?: "idea")
                val answer = GemmaHolder.generate(ctx, CleanupText.prompt(segs, p.instruction, p.checklist, java.time.LocalDate.now().toString(), p.mixed))
                val ms = SystemClock.elapsedRealtime() - t0
                Log.i(TAG, "Type ${p.id}: checklist=${p.checklist} mixed=${p.mixed}")
                Log.i(TAG, "Answer after $ms ms (${segs.size} segments):\n$answer")
                when (val r = CleanupText.check(answer, segs, p.mixed)) {
                    is CleanupText.Result.Ok -> Log.i(TAG, "ACCEPTED\n" + CleanupText.cleanBlock(r.bullets, segs))
                    is CleanupText.Result.Rejected -> Log.i(TAG, "REJECTED: ${r.reason}")
                }
            }.onFailure { Log.e(TAG, "Probe failed", it) }
            runOnUiThread { finish() }
        }
    }

    companion object {
        private const val TAG = "CleanupProbe"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
