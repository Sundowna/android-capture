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

    /**
     * The user picked another type for a finished note: clean it (and everything added to it) again
     * with that type's instruction. Each section's old clean block in the vault is swapped for the
     * new one; the exact words stay. If the new answer fails the check, the old version stays.
     */
    /** What a re-clean replaced: the note's rows and the vault note before and after, for Undo. */
    data class RecleanUndo(val noteId: String, val rows: List<Capture>, val path: String?, val before: String?, val after: String?)
    val lastReclean = kotlinx.coroutines.flow.MutableStateFlow<RecleanUndo?>(null)

    /** Puts the previous type and clean version back, in the app and (if untouched since) in the vault. */
    suspend fun undoReclean(ctx: Context) = withContext(Dispatchers.IO) {
        val u = lastReclean.value ?: return@withContext
        lastReclean.value = null
        val dao = CaptureDb.get(ctx).dao()
        if (u.path != null && u.before != null) {
            val vault = VaultWriter(ctx, Prefs(ctx).vaultUri ?: return@withContext)
            if (vault.read(u.path) == u.after) {
                dao.putEdit(PendingEdit(u.noteId, u.path, u.after ?: "", u.before))
                vault.overwrite(u.path, u.before)
                dao.clearEdit(u.noteId)
            }
        }
        u.rows.forEach { dao.upsert(it) }
        Notes.forget(u.noteId)
        dao.touch(u.noteId, System.currentTimeMillis())
    }

    suspend fun reclean(ctx: Context, noteId: String, newType: String) = withContext(Dispatchers.IO) {
        val dao = CaptureDb.get(ctx).dao()
        val note = dao.get(noteId) ?: return@withContext
        val rows = listOf(note) + dao.additions(noteId)
        // The vault note as it is now, so Undo can put it back exactly.
        val vault = if (note.isPrivate) null else Prefs(ctx).vaultUri?.let { VaultWriter(ctx, it) }
        val path = vault?.let { Notes.locate(ctx, it, note) }
        val before = path?.let { vault.read(it) }
        val sections = (listOf(note) + dao.additions(noteId)).filter { it.kind == "voice" && !it.transcript.isNullOrBlank() }
        (listOf(note) + dao.additions(noteId)).forEach { dao.setType(it.id, newType) }
        val profile = org.brain.capture.types.Profiles.get(ctx, newType)
        // Show "Cleaning" for every section that will change, then work through them in order.
        sections.forEach { dao.clearClean(it.id) }
        for (s in sections) {
            val result = runCatching { recleanOne(ctx, note.copy(type = newType), s, profile) }.getOrElse { t ->
                Log.e(TAG, "Re-clean failed for ${s.id}", t)
                dao.restoreClean(s.id, s.clean, s.cleanup)
                "failed: ${t.message ?: t.javaClass.simpleName}"
            }
            Log.i(TAG, "${s.id} re-cleaned as ${profile.name}: $result")
        }
        dao.touch(noteId, System.currentTimeMillis())
        lastReclean.value = RecleanUndo(noteId, rows, path, before, path?.let { vault.read(it) })
        org.brain.capture.ui.BrainWidget.refresh(ctx)
    }

    private suspend fun recleanOne(ctx: Context, owner: Capture, s: Capture, profile: org.brain.capture.types.CaptureProfile): String {
        val dao = CaptureDb.get(ctx).dao()
        val transcript = s.transcript!!.trim()
        val segments = CleanupText.segments(transcript)
        val result = answer(ctx, s.id, segments, profile)
        val ok = result as? CleanupText.Result.Ok ?: run {
            dao.restoreClean(s.id, s.clean, s.cleanup) // keep the version that was there
            return "rejected, kept the previous version: ${(result as CleanupText.Result.Rejected).reason}"
        }
        val stored = CleanupText.stored(ok.title, ok.bullets)
        if (owner.isPrivate) { dao.restoreClean(s.id, stored, "done (re-cleaned as ${profile.name}, app only)"); return "done" }

        val vault = VaultWriter(ctx, Prefs(ctx).vaultUri ?: throw IllegalStateException("No vault"))
        val path = Notes.locate(ctx, vault, owner) ?: throw IllegalStateException("Note not found")
        val note = vault.read(path) ?: throw IllegalStateException("Note not found")
        val block = CleanupText.cleanBlock(ok.bullets, segments)
        val oldBlock = s.clean?.let { CleanupText.cleanBlock(CleanupText.bulletsFromStored(it), segments) }
        // The old clean block if there was one; otherwise the raw words (a note the check had kept raw).
        val swapped = oldBlock?.let { CleanupText.replaceBlock(note, it, block) }
            ?: CleanupText.rewrite(note, transcript, block, GemmaHolder.modelName(ctx), addition = true)
        val updated = swapped?.let { if (s.id == owner.id) CleanupText.withCaptureType(it, profile.id) else it }
        if (updated == null) {
            // The note was edited by hand meanwhile: the app's copy changes, the vault note stays as it is.
            dao.restoreClean(s.id, stored, "done in the app only (note was edited)")
            return "note was changed, vault left alone"
        }
        dao.putEdit(PendingEdit(s.id, path, note, updated))
        vault.overwrite(path, updated)
        dao.clearEdit(s.id)
        Notes.forget(owner.id)
        dao.restoreClean(s.id, stored, "done (re-cleaned as ${profile.name})")
        return "done"
    }

    /** Gemma's answer for [segments] with [profile]'s instruction, checked; one retry if sources are missing. */
    private suspend fun answer(ctx: Context, id: String, segments: List<String>, profile: org.brain.capture.types.CaptureProfile): CleanupText.Result {
        val prompt = CleanupText.prompt(segments, profile.instruction, profile.checklist, java.time.LocalDate.now().toString())
        cleaning.value = id
        try {
            var result = CleanupText.check(GemmaHolder.generate(ctx, prompt), segments)
            if (result is CleanupText.Result.Rejected && result.retry)
                result = CleanupText.check(GemmaHolder.generate(ctx, prompt +
                    "\nImportant: end EVERY line with its segment numbers in square brackets, e.g. [3]."), segments)
            return result
        } finally { cleaning.value = null }
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
        // The note's capture type decides the instruction (editable in Settings → Capture types).
        val profile = org.brain.capture.types.Profiles.get(ctx, owner.type)
        val prompt = CleanupText.prompt(segments, profile.instruction, profile.checklist, today)
        var result: CleanupText.Result
        try {
            var answer = GemmaHolder.generate(ctx, prompt)
            Log.i(TAG, "Gemma answered for ${segments.size} segments:\n$answer")
            result = CleanupText.check(answer, segments)
            // Nothing said to list (or every line made up): no retry, the note keeps the exact words.
            if (result is CleanupText.Result.Rejected && result.retry) {
                // One more try with the rule spelled out; a second miss keeps the exact words.
                Log.i(TAG, "Retrying after: ${result.reason}")
                answer = GemmaHolder.generate(ctx, prompt + "\nImportant: end EVERY line with its segment numbers in square brackets, e.g. [3].")
                Log.i(TAG, "Second answer:\n$answer")
                result = CleanupText.check(answer, segments)
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
