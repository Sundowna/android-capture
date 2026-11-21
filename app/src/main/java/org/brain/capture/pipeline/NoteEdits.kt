package org.brain.capture.pipeline

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.brain.capture.Prefs
import org.brain.capture.cleanup.CleanupStep
import org.brain.capture.cleanup.CleanupText
import org.brain.capture.data.Capture
import org.brain.capture.data.CaptureDb
import org.brain.capture.data.PendingEdit
import org.brain.capture.share.AppScope
import org.brain.capture.vault.VaultUnavailableException
import org.brain.capture.vault.VaultWriter

/**
 * Editing a note on the note screen, one section at a time (the note itself, or something added to
 * it), in the view the user is looking at: the exact words, or the clean version. The vault note is
 * changed only where the app wrote that section; if it was changed in Obsidian meanwhile, nothing
 * is written and the user is told to edit it there.
 */
object NoteEdits {
    private const val CHANGED = "This part was changed in Obsidian, so it was left as it is. Edit it there."

    /** Sections whose exact words can be edited: voice and typed text (links and files have their own layout). */
    fun wordsEditable(c: Capture) = c.kind == "voice" || c.kind == "text"

    /**
     * New exact words for a section. Its clean version (if any) gives way to the corrected words in
     * the note, and a voice section is then cleaned again from them, in the background.
     */
    suspend fun editWords(ctx: Context, id: String, text: String) = withContext(Dispatchers.IO) {
        val dao = CaptureDb.get(ctx).dao()
        val s = dao.get(id) ?: error("Note not found")
        val owner = s.appendTo?.let { dao.get(it) } ?: s
        val new = text.trim()
        val old = s.transcript?.trim().orEmpty()
        if (new == old) return@withContext
        if (new.isEmpty()) error("Nothing left. To remove this, use Undo or delete the note.")
        if (!owner.isPrivate) write(ctx, owner, s.id) { note ->
            val cleanBlock = s.clean?.let { CleanupText.cleanBlock(CleanupText.bulletsFromStored(it), CleanupText.segments(old)) }
            cleanBlock?.let { CleanupText.replaceBlock(note, it, new) } ?: CleanupText.replaceWords(note, old, new)
        }
        val voice = s.kind == "voice"
        dao.upsert(s.copy(transcript = new, clean = if (voice) null else s.clean, cleanup = if (voice) null else s.cleanup))
        done(ctx, owner)
        if (voice) AppScope.launch { CleanupStep.cleanNow(ctx.applicationContext, s.id); Notes.forget(owner.id) }
    }

    /** A hand-edited clean version for a section; its exact words stay as they are. */
    suspend fun editClean(ctx: Context, id: String, text: String) = withContext(Dispatchers.IO) {
        val dao = CaptureDb.get(ctx).dao()
        val s = dao.get(id) ?: error("Note not found")
        val stored = s.clean ?: error("There is no clean version to edit")
        val owner = s.appendTo?.let { dao.get(it) } ?: s
        val old = CleanupText.bulletsFromStored(stored)
        val new = CleanupText.fromEditable(text, old)
        if (new == old) return@withContext
        if (new.isEmpty()) error("Nothing left. To remove this, use Undo or delete the note.")
        val title = CleanupText.parseStored(stored).firstOrNull { it.title }?.text
        if (!owner.isPrivate) write(ctx, owner, s.id) { note ->
            val segments = CleanupText.segments(s.transcript.orEmpty())
            CleanupText.replaceBlock(note, CleanupText.cleanBlock(old, segments), CleanupText.cleanBlock(new, segments))
        }
        dao.restoreClean(s.id, CleanupText.stored(title, new), "edited by hand")
        done(ctx, owner)
    }

    /** Reads the owner's vault note, applies [change] (null = not there as the app wrote it), writes it safely. */
    private suspend fun write(ctx: Context, owner: Capture, sectionId: String, change: (String) -> String?) {
        val vault = VaultWriter(ctx, Prefs(ctx).vaultUri ?: throw VaultUnavailableException("No vault folder selected"))
        val path = Notes.locate(ctx, vault, owner) ?: throw VaultUnavailableException("Note not found")
        val note = vault.read(path) ?: throw VaultUnavailableException("Note not found")
        val updated = change(note) ?: error(CHANGED)
        val dao = CaptureDb.get(ctx).dao()
        dao.putEdit(PendingEdit(sectionId, path, note, updated)) // backup in case of a crash mid-write
        vault.overwrite(path, updated)
        dao.clearEdit(sectionId)
    }

    private suspend fun done(ctx: Context, owner: Capture) {
        val dao = CaptureDb.get(ctx).dao()
        dao.touch(owner.id, System.currentTimeMillis())
        Notes.forget(owner.id)
        // An edit is the newest action, and Undo cannot take it back: older actions are no longer offered.
        Undo.drop()
        org.brain.capture.ui.BrainWidget.refresh(ctx)
    }
}
