package org.brain.capture.pipeline

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.brain.capture.data.Capture
import org.brain.capture.data.CaptureDb
import org.brain.capture.data.CaptureStates
import org.brain.capture.data.CaptureTypes
import org.brain.capture.share.Shares
import java.util.UUID

/** Typed or pasted text from the capture screen. */
object TextCapture {
    /**
     * Open captures go through the same path as text shared from another app (a pasted link
     * becomes a link note). Private ones stay in the app's database only.
     */
    suspend fun save(ctx: Context, text: String, type: String, ai: Boolean = false): Capture = withContext(Dispatchers.IO) {
        val dao = CaptureDb.get(ctx).dao()
        if (org.brain.capture.types.Profiles.isPrivateId(type)) { // the built-in Private and every private type made in Settings
            val now = System.currentTimeMillis()
            return@withContext Capture(id = UUID.randomUUID().toString(), createdAt = now, state = CaptureStates.SAVED,
                kind = "text", transcript = text, type = type, cleanup = if (ai) AI_PENDING else TYPED).also {
                dao.upsert(it); Undo.record(Undo.Action.Created(it.id)); org.brain.capture.ui.BrainWidget.refresh(ctx)
                if (ai) cleanLater(ctx, it.id)
            }
        }
        val staged = Shares.stage(ctx, Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
            null) {}
        dao.setType(staged.id, type)
        Shares.save(ctx, staged.id)
        org.brain.capture.ui.BrainWidget.refresh(ctx)
        val saved = dao.get(staged.id) ?: error("Saved note not found")
        // A pasted link stays a link; plain text is cleaned by the AI only when asked ("Save with AI").
        if (saved.kind == "text") { dao.setCleanup(saved.id, if (ai) AI_PENDING else TYPED); if (ai) cleanLater(ctx, saved.id) }
        dao.get(staged.id) ?: saved
    }

    /** What a typed or pasted note's cleanup says when it was saved without the AI. */
    const val TYPED = "skipped: typed"
    /** Saved with "Save with AI" and not cleaned yet (the cleanup result replaces it). */
    const val AI_PENDING = "pending: typed, with AI"

    /** "Save with AI": the text is cleaned in the background with the note's type, like a voice note. */
    private fun cleanLater(ctx: Context, id: String) = org.brain.capture.share.AppScope.launch {
        org.brain.capture.cleanup.CleanupStep.cleanNow(ctx.applicationContext, id)
    }

    /**
     * Typed or pasted text added to the note on screen: an "Added 13:05:12 (typed)" section in the
     * vault note (private notes: in the app only), and a row of its own so it shows in the note
     * screen and Undo can take it back.
     */
    suspend fun append(ctx: Context, note: Capture, text: String, ai: Boolean = false): Capture = withContext(Dispatchers.IO) {
        val dao = CaptureDb.get(ctx).dao()
        val now = System.currentTimeMillis()
        if (!note.isPrivate) {
            val vault = org.brain.capture.vault.VaultWriter(ctx, org.brain.capture.Prefs(ctx).vaultUri
                ?: throw org.brain.capture.vault.VaultUnavailableException("No vault folder selected"))
            val path = Notes.locate(ctx, vault, note) ?: throw org.brain.capture.vault.VaultUnavailableException("Note not found")
            val at = java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), java.time.ZoneId.systemDefault())
            if (!vault.append(path, org.brain.capture.vault.NoteBuilder.appendBlock(at, text, null, null, typed = true)))
                throw org.brain.capture.vault.VaultUnavailableException("Note not found")
        }
        val c = Capture(id = UUID.randomUUID().toString(), createdAt = now, state = CaptureStates.SAVED, kind = "text",
            transcript = text, type = note.type, appendTo = note.id, appended = true, cleanup = if (ai) AI_PENDING else TYPED)
        dao.upsert(c)
        dao.touch(note.id, now)
        Notes.forget(note.id)
        Undo.record(Undo.Action.Added(c.id))
        if (ai) cleanLater(ctx, c.id)
        c
    }
}
