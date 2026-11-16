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

        val t0 = SystemClock.elapsedRealtime()
        val answer = GemmaHolder.generate(ctx, CleanupText.prompt(segments))
        val ms = SystemClock.elapsedRealtime() - t0
        Log.i(TAG, "Gemma answered in $ms ms for ${segments.size} segments:\n$answer")

        val bullets = when (val r = CleanupText.check(answer, segments.size)) {
            is CleanupText.Result.Rejected -> return "rejected: ${r.reason}"
            is CleanupText.Result.Ok -> r.bullets
        }
        val dao = CaptureDb.get(ctx).dao()
        // An addition lives in the note it was added to; clean it there.
        val owner = c.appendTo?.let { dao.get(it) ?: return "skipped: note not found" } ?: c
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
