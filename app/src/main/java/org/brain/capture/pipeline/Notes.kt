package org.brain.capture.pipeline

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.brain.capture.Prefs
import org.brain.capture.data.Capture
import org.brain.capture.data.CaptureDb
import org.brain.capture.data.CaptureStates
import org.brain.capture.data.PendingEdit
import org.brain.capture.vault.NoteBuilder
import org.brain.capture.vault.NoteText
import org.brain.capture.vault.VaultUnavailableException
import org.brain.capture.vault.VaultWriter
import java.io.File
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What a card shows for one note. */
data class NoteView(val text: String, val fromVault: Boolean, val missing: Boolean = false)

/**
 * Reading and changing notes for the transcript cards. The vault is the source of truth:
 * cards show the note as it is now, including edits made in Obsidian.
 */
object Notes {
    private const val TAG = "Notes"
    private val cache = ConcurrentHashMap<String, NoteView>()

    fun cached(id: String): NoteView? = cache[id]

    /** Current path of [c]'s note; finds it by capture_id if it was moved or renamed. */
    suspend fun locate(ctx: Context, vault: VaultWriter, c: Capture): String? {
        val path = c.notePath ?: return null
        val text = vault.read(path)
        if (text != null && (NoteText.captureId(text) ?: c.id) == c.id) return path
        val found = vault.findNoteById(c.id) ?: return null
        CaptureDb.get(ctx).dao().moveNote(c.id, found)
        Log.i(TAG, "Note for ${c.id} moved: $path -> $found")
        return found
    }

    /** Loads the live note text for a card. Falls back to the app's copy if the vault can't be read. */
    suspend fun load(ctx: Context, c: Capture): NoteView = withContext(Dispatchers.IO) {
        val view = runCatching {
            val vault = vault(ctx)
            val path = locate(ctx, vault, c)
            val text = path?.let { vault.read(it) }
            if (text == null) NoteView(c.transcript.orEmpty(), fromVault = false, missing = true)
            else NoteView(NoteText.display(text), fromVault = true)
        }.getOrElse { NoteView(c.transcript.orEmpty(), fromVault = false) }
        cache[c.id] = view
        view
    }

    /** Raw body (without frontmatter) for the editor, plus the full note to compare against on save. */
    suspend fun openForEdit(ctx: Context, c: Capture): Pair<String, String> = withContext(Dispatchers.IO) {
        val vault = vault(ctx)
        val path = locate(ctx, vault, c) ?: throw VaultUnavailableException("Note not found")
        val note = vault.read(path) ?: throw VaultUnavailableException("Note not found")
        NoteText.split(note).second.trim() to note
    }

    /**
     * Saves an edited body, but only if the note is still exactly as it was when the editor
     * opened. Returns false if it was changed in the meantime (e.g. in Obsidian).
     */
    suspend fun saveEdit(ctx: Context, c: Capture, original: String, body: String): Boolean = withContext(Dispatchers.IO) {
        val vault = vault(ctx)
        val path = locate(ctx, vault, c) ?: throw VaultUnavailableException("Note not found")
        if (vault.read(path) != original) return@withContext false
        val replacement = NoteText.withBody(original, body)
        val dao = CaptureDb.get(ctx).dao()
        dao.putEdit(PendingEdit(c.id, path, original, replacement)) // backup in case of a crash mid-write
        vault.overwrite(path, replacement)
        dao.clearEdit(c.id)
        dao.touch(c.id, System.currentTimeMillis())
        cache.remove(c.id)
        true
    }

    /** Adds typed text to the end of [c]'s note. */
    suspend fun appendTyped(ctx: Context, c: Capture, text: String) = withContext(Dispatchers.IO) {
        val vault = vault(ctx)
        val path = locate(ctx, vault, c) ?: throw VaultUnavailableException("Note not found")
        if (!vault.append(path, NoteBuilder.appendBlock(OffsetDateTime.now(), text.trim(), null, null, typed = true)))
            throw VaultUnavailableException("Note not found")
        CaptureDb.get(ctx).dao().touch(c.id, System.currentTimeMillis())
        cache.remove(c.id)
    }

    /**
     * Finishes or undoes an edit that was interrupted while writing: if the note is neither the old
     * nor the new version (a partial write), the backup is restored.
     */
    suspend fun recoverEdits(ctx: Context) = withContext(Dispatchers.IO) {
        val dao = CaptureDb.get(ctx).dao()
        val pending = dao.pendingEdits()
        if (pending.isEmpty()) return@withContext
        val vault = runCatching { vault(ctx) }.getOrNull() ?: return@withContext
        for (e in pending) runCatching {
            val now = vault.read(e.notePath)
            if (now != null && now != e.original && now != e.replacement) {
                vault.overwrite(e.notePath, e.original)
                Log.w(TAG, "Restored ${e.notePath} after an interrupted edit")
            }
            dao.clearEdit(e.captureId)
        }
    }

    /** One-time import of the transcript history file from before the database existed. */
    suspend fun importHistory(ctx: Context) = withContext(Dispatchers.IO) {
        val dao = CaptureDb.get(ctx).dao()
        val file = File(ctx.filesDir, "history.jsonl")
        if (!file.exists()) return@withContext
        val vault = runCatching { vault(ctx) }.getOrNull()
        for (line in file.readLines()) runCatching {
            val o = org.json.JSONObject(line)
            val path = o.optString("note").ifEmpty { null } ?: return@runCatching
            if (dao.byNote(path) != null) return@runCatching
            val id = vault?.read(path)?.let(NoteText::captureId) ?: UUID.randomUUID().toString()
            dao.upsert(Capture(
                id = id, createdAt = o.getLong("at"), state = CaptureStates.SAVED,
                transcript = o.getString("text"), audioSeconds = o.getDouble("audio"),
                modelLoadMs = o.getLong("load"), transcribeMs = o.getLong("tx"), chunks = o.getInt("chunks"),
                notePath = path,
            ))
        }
        file.renameTo(File(ctx.filesDir, "history.jsonl.imported"))
    }

    /** What sharing a card sends: the note text and copies of the files it embeds. */
    class ShareBundle(val text: String, val files: List<Pair<File, String>>) // file, mime type

    /**
     * Collects a note for sharing to another app: the readable text (no frontmatter, no embed
     * lines) and the audio, photos and documents it embeds, copied to the app's cache so the
     * other app gets read access to just these files. The previous share's copies are removed.
     */
    suspend fun shareBundle(ctx: Context, c: Capture): ShareBundle = withContext(Dispatchers.IO) {
        val out = File(ctx.cacheDir, "outgoing").apply { deleteRecursively(); mkdirs() }
        val vault = runCatching { vault(ctx) }.getOrNull()
        val path = vault?.let { runCatching { locate(ctx, it, c) }.getOrNull() }
        val note = path?.let { vault.read(it) }
        var text = note?.let(NoteText::display) ?: c.transcript.orEmpty()
        if (c.kind == "file") text = text.lines().drop(1).joinToString("\n").trim() // drop "Shared from …"
        val files = mutableListOf<Pair<File, String>>()
        if (vault != null && note != null) {
            val folder = path.substringBeforeLast('/', "")
            val inbox = Prefs(ctx).inboxFolder
            val names = Regex("""!\[\[([^\]|#]+)""").findAll(note).map { it.groupValues[1].trim() }.distinct()
            for (name in names) {
                val candidates = listOf("$folder/Attachments/$name", "$folder/$name", "$inbox/Attachments/$name", name)
                    .map { it.trimStart('/') }.distinct()
                val uri = candidates.firstNotNullOfOrNull { runCatching { vault.uriOf(it) }.getOrNull() } ?: continue
                runCatching {
                    val dest = File(out, File(name).name)
                    ctx.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
                    val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
                    files += dest to mime
                }.onFailure { Log.w(TAG, "Could not attach $name", it) }
            }
        }
        ShareBundle(text, files)
    }

    private fun vault(ctx: Context): VaultWriter =
        VaultWriter(ctx, Prefs(ctx).vaultUri ?: throw VaultUnavailableException("No vault folder selected"))
}
