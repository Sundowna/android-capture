package org.brain.capture.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.brain.capture.Prefs
import org.brain.capture.data.Capture
import org.brain.capture.data.CaptureDb
import org.brain.capture.data.CaptureStates
import org.brain.capture.vault.NoteBuilder
import org.brain.capture.vault.VaultUnavailableException
import org.brain.capture.vault.VaultWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/** Work that should finish even if the share panel closes. */
object AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Shared links, text and files: first copied into app-private staging (Android's permission to
 * read a shared file can expire), then written to the vault. Staged shares that could not be
 * written (vault unavailable, app killed) are retried automatically.
 */
object Shares {
    private const val TAG = "Shares"
    private val lock = Mutex()

    private fun dir(ctx: Context, id: String) = File(ctx.filesDir, "shares/$id")
    private fun metaFile(dir: File) = File(dir, "meta.json")

    /** Copies everything out of the share intent. [progress] gets 0..1 when sizes are known, else null. */
    suspend fun stage(ctx: Context, intent: Intent, sourceApp: String?, progress: (Float?) -> Unit): Capture =
        withContext(Dispatchers.IO) {
            val cr = ctx.contentResolver
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: intent.getStringExtra(Intent.EXTRA_TITLE)
            val uris = streams(intent)

            val id = UUID.randomUUID().toString()
            val d = dir(ctx, id).apply { mkdirs() }
            val meta = JSONObject().put("sourceApp", sourceApp ?: "")
                .put("sourceName", Previews.appName(ctx, sourceApp) ?: "")
            val files = JSONArray()
            var fingerprint: String? = null
            val kind: String

            if (uris.isNotEmpty()) {
                kind = "file"
                val infos = uris.map { u ->
                    val (name, size) = cr.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) (c.getString(0) to (if (c.isNull(1)) -1L else c.getLong(1))) else null to -1L }
                        ?: (null to -1L)
                    Triple(u, name, size)
                }
                val total = infos.sumOf { it.third.coerceAtLeast(0) }.takeIf { infos.all { it.third >= 0 } && it > 0 }
                var done = 0L
                val hashes = StringBuilder()
                infos.forEachIndexed { i, (u, name, _) ->
                    val mime = cr.getType(u) ?: intent.type
                    val staged = File(d, "f$i")
                    val md = MessageDigest.getInstance("SHA-256")
                    (cr.openInputStream(u) ?: error("Cannot read the shared file")).use { input ->
                        staged.outputStream().use { out ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                out.write(buf, 0, n); md.update(buf, 0, n); done += n
                                progress(total?.let { (done.toFloat() / it).coerceAtMost(1f) })
                            }
                        }
                    }
                    val sha = md.digest().joinToString("") { "%02x".format(it) }
                    hashes.append(sha)
                    val ext = name?.substringAfterLast('.', "")?.ifEmpty { null }
                        ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
                    // Thumbnail (images, PDF first page) and page count, made now while the file is at hand.
                    val preview = Previews.make(staged, mime, File(ctx.filesDir, "thumbs/$id-$i.jpg"))
                    files.put(JSONObject().put("staged", staged.name).put("name", name ?: "")
                        .put("thumb", preview.thumb?.path ?: "").put("pages", preview.pages ?: -1)
                        .put("ext", ext).put("mime", mime ?: "").put("size", staged.length()).put("sha256", sha))
                }
                fingerprint = if (uris.size == 1) hashes.toString() else sha256(hashes.toString())
                meta.put("text", text ?: "").put("title", subject ?: "")
            } else {
                val link = ShareParse.link(text, subject)
                if (link != null) {
                    kind = "link"
                    fingerprint = link.url
                    meta.put("url", link.url).put("title", link.title ?: "").put("text", link.extraText ?: "")
                } else {
                    if (text.isNullOrBlank()) { d.deleteRecursively(); error("There was nothing to save in this share.") }
                    kind = "text"
                    fingerprint = sha256(text.trim())
                    meta.put("text", text.trim()).put("title", subject ?: "")
                }
            }
            meta.put("files", files)
            metaFile(d).writeText(meta.toString())
            Capture(id = id, createdAt = System.currentTimeMillis(), state = CaptureStates.RECORDED, kind = kind,
                fingerprint = fingerprint, stagingPath = d.path).also { CaptureDb.get(ctx).dao().upsert(it) }
        }

    /** The same link or file saved in the last 24 hours, if any. */
    suspend fun duplicateOf(ctx: Context, c: Capture): Capture? = c.fingerprint?.let {
        CaptureDb.get(ctx).dao().recentShare(it, System.currentTimeMillis() - 24 * 3600_000L)
    }

    /** Drops a staged share the user chose not to save again. */
    suspend fun discard(ctx: Context, c: Capture) = withContext(Dispatchers.IO) {
        c.stagingPath?.let { File(it).deleteRecursively() }
        CaptureDb.get(ctx).dao().upsert(c.copy(state = "discarded", stagingPath = null))
    }

    /** Writes a staged share to the vault. Returns the note's vault path. Safe to call again after a failure. */
    suspend fun save(ctx: Context, captureId: String): String = lock.withLock {
        withContext(Dispatchers.IO) {
            val dao = CaptureDb.get(ctx).dao()
            var c = dao.get(captureId) ?: error("Share not found")
            if (c.state == CaptureStates.SAVED) return@withContext c.notePath.orEmpty()
            val d = File(c.stagingPath ?: error("Share content missing"))
            val meta = JSONObject(metaFile(d).readText())
            val prefs = Prefs(ctx)
            val vault = VaultWriter(ctx, prefs.vaultUri ?: throw VaultUnavailableException("No vault folder selected"))
            val inbox = prefs.inboxFolder.trim('/')
            val at = OffsetDateTime.ofInstant(Instant.ofEpochMilli(c.createdAt), ZoneId.systemDefault())
            try {
                // Files first, so the note never links to something missing. Each finished file is
                // remembered, so a retry does not copy it twice.
                val files = meta.getJSONArray("files")
                for (i in 0 until files.length()) {
                    val f = files.getJSONObject(i)
                    if (f.optString("vaultName").isNotEmpty()) continue
                    val staged = File(d, f.getString("staged"))
                    val original = f.optString("name").substringBeforeLast('.').ifEmpty { null }
                    val base = NoteBuilder.fileBaseName(at, original)
                    val name = vault.write("$inbox/Attachments", base, f.getString("ext")) { out ->
                        staged.inputStream().use { it.copyTo(out, 1 shl 16) }
                    }
                    f.put("vaultName", name)
                    metaFile(d).writeText(meta.toString())
                }

                val folder = "$inbox/" + when (c.kind) { "link" -> "Links"; "text" -> "Text"; else -> "Documents" }
                val path = vault.findNoteById(c.id, maxFiles = 500, startFolder = folder) ?: run {
                    val (base, note) = buildNote(c, meta, at)
                    vault.cleanupTemp(folder)
                    "$folder/" + vault.write(folder, base, "md") { it.write(note.toByteArray()) }
                }
                c = c.copy(state = CaptureStates.SAVED, notePath = path, error = null, details = details(meta),
                    transcript = noteText(c, meta), lastActivityAt = System.currentTimeMillis())
                dao.upsert(c)
                d.deleteRecursively()
                path
            } catch (t: Throwable) {
                dao.upsert(c.copy(state = CaptureStates.FAILED, error = t.message))
                throw t
            }
        }
    }

    /** Retries every share that is staged but not yet in the vault. */
    fun resumePending(ctx: Context) {
        AppScope.launch {
            for (c in CaptureDb.get(ctx).dao().pendingShares()) {
                if (c.state == "discarded" || c.stagingPath == null || !File(c.stagingPath).exists()) continue
                runCatching { save(ctx, c.id) }.onFailure { Log.w(TAG, "Share ${c.id} still not saved: ${it.message}") }
            }
        }
    }

    private fun buildNote(c: Capture, meta: JSONObject, at: OffsetDateTime): Pair<String, String> {
        val source = NoteBuilder.Source(meta.optString("sourceName").ifEmpty { null }, meta.optString("sourceApp").ifEmpty { null })
        val text = meta.optString("text").ifEmpty { null }
        val title = meta.optString("title").ifEmpty { null }
        return when (c.kind) {
            "link" -> {
                val url = meta.getString("url")
                NoteBuilder.baseName(at, "Link", title ?: ShareParse.domain(url)) to
                    NoteBuilder.linkNote(c.id, at, url, title, text, source)
            }
            "text" -> NoteBuilder.baseName(at, "Text", title ?: text) to NoteBuilder.textNote(c.id, at, text.orEmpty(), source)
            else -> {
                val files = meta.getJSONArray("files")
                val list = (0 until files.length()).map { files.getJSONObject(it) }
                val kind = ShareParse.fileKind(list.map { it.optString("mime") })
                val label = list.firstOrNull()?.optString("name")?.substringBeforeLast('.')?.ifEmpty { null } ?: title
                NoteBuilder.baseName(at, kind, label) to NoteBuilder.fileNote(
                    c.id, at, list.map { NoteBuilder.SharedFile(it.getString("vaultName"), it.optString("mime"), it.optLong("size"), it.optString("sha256"), it.optInt("pages", -1)) },
                    text, source)
            }
        }
    }

    /** What the card shows for a share: where it came from and which files were saved. */
    private fun details(meta: JSONObject): String {
        val files = meta.getJSONArray("files")
        val out = JSONArray()
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            out.put(JSONObject().put("name", f.optString("vaultName")).put("mime", f.optString("mime"))
                .put("size", f.optLong("size")).put("pages", f.optInt("pages", -1)).put("thumb", f.optString("thumb")))
        }
        return JSONObject().put("source", meta.optString("sourceName")).put("files", out).toString()
    }

    /** Short text kept in the database as the card's fallback when the vault can't be read. */
    private fun noteText(c: Capture, meta: JSONObject): String = when (c.kind) {
        "link" -> listOf(meta.optString("title"), meta.optString("url"), meta.optString("text")).filter { it.isNotEmpty() }.joinToString("\n")
        "text" -> meta.optString("text")
        else -> meta.getJSONArray("files").let { f -> (0 until f.length()).joinToString("\n") { f.getJSONObject(it).optString("vaultName") } }
    }

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> {
        val list = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let(list::add)
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(list::addAll)
        }
        if (list.isEmpty()) intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(list::add) }
        return list.distinct()
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
