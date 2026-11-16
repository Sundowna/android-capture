package org.brain.capture.cleanup

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.brain.capture.Prefs
import org.brain.capture.data.Capture
import org.brain.capture.data.CaptureDb
import org.brain.capture.data.PendingEdit
import org.brain.capture.pipeline.Notes
import org.brain.capture.vault.VaultWriter

/**
 * Runs after a voice note is saved: Gemma rewrites the Whisper transcript as clean bullets, every
 * bullet must cite the transcript segments it came from, and only then is the note rewritten
 * (clean version on top, exact words in a collapsed quote). Anything unexpected leaves the raw note.
 */
object CleanupStep {
    private const val TAG = "Cleanup"
    private const val DAY = 24 * 60 * 60 * 1000L

    /** Id of the capture Gemma is cleaning right now, for the "Cleaning…" state on screen. */
    val cleaning = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Cleans every recent voice note that has not been tried yet. */
    suspend fun runPending(ctx: Context) = withContext(Dispatchers.IO) {
        if (GemmaHolder.modelFile(ctx) == null) return@withContext
        val dao = CaptureDb.get(ctx).dao()
        for (c in dao.pendingCleanup(System.currentTimeMillis() - DAY)) {
            val result = runCatching { clean(ctx, c) }.getOrElse { t ->
                Log.e(TAG, "Cleanup failed for ${c.id}", t)
                "failed: ${t.message ?: t.javaClass.simpleName}"
            }
            dao.setCleanup(c.id, result)
            Log.i(TAG, "${c.id}: $result")
        }
    }

    private suspend fun clean(ctx: Context, c: Capture): String {
        val transcript = c.transcript?.trim().orEmpty()
        if (transcript.isEmpty()) return "skipped: no speech"
        val segments = CleanupText.segments(transcript)

        val dao = CaptureDb.get(ctx).dao()
        // An addition lives in the note it was added to; clean it there, as that note's type.
        val owner = c.appendTo?.let { dao.get(it) ?: return "skipped: note not found" } ?: c
        val today = java.time.LocalDate.now().toString()

        cleaning.value = c.id
        val t0 = SystemClock.elapsedRealtime()
        val prompt = CleanupText.prompt(segments, owner.type, today)
        var result: CleanupText.Result
        try {
            var answer = GemmaHolder.generate(ctx, prompt)
            Log.i(TAG, "Gemma answered for ${segments.size} segments:\n$answer")
            result = CleanupText.check(answer, segments.size)
            if (result is CleanupText.Result.Rejected) {
                // One more try with the rule spelled out; a second miss keeps the exact words.
                Log.i(TAG, "Retrying after: ${result.reason}")
                answer = GemmaHolder.generate(ctx, prompt + "\nImportant: end EVERY line with its segment numbers in square brackets, e.g. [3].")
                Log.i(TAG, "Second answer:\n$answer")
                result = CleanupText.check(answer, segments.size)
            }
        } finally { cleaning.value = null }
        val ms = SystemClock.elapsedRealtime() - t0

        val ok = when (val r = result) {
            is CleanupText.Result.Rejected -> return "rejected: ${r.reason}"
            is CleanupText.Result.Ok -> r
        }
        val bullets = ok.bullets
        // The app's own copy first: the capture screen and the card show it at once.
        dao.setClean(c.id, CleanupText.stored(ok.title, bullets))
        if (owner.isPrivate) {
            dao.touch(owner.id, System.currentTimeMillis())
            return "done in $ms ms (private, app only)"
        }
        val vault = VaultWriter(ctx, Prefs(ctx).vaultUri ?: return "skipped: no vault")
        val path = Notes.locate(ctx, vault, owner) ?: return "skipped: note not found"
        val note = vault.read(path) ?: return "skipped: note not found"
        val block = CleanupText.cleanBlock(bullets, segments)
        val updated = CleanupText.rewrite(note, transcript, block, GemmaHolder.modelName(ctx), addition = owner !== c)
            ?: return "skipped: note was changed"

        dao.putEdit(PendingEdit(c.id, path, note, updated)) // backup in case of a crash mid-write
        vault.overwrite(path, updated)
        dao.clearEdit(c.id)
        Notes.forget(owner.id)
        dao.touch(owner.id, System.currentTimeMillis())
        return "done in $ms ms"
    }
}
