package org.brain.capture.vault

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.OutputStream
import java.util.UUID

class VaultUnavailableException(message: String) : Exception(message)

/**
 * Writes files into the user-selected vault folder through the Storage Access Framework.
 * Uses DocumentsContract directly (DocumentFile is very slow on large folders).
 *
 * Every file is written under a hidden temporary name first and renamed once complete, so
 * Obsidian and sync tools never see a half-written file. Existing files are never overwritten.
 */
class VaultWriter(context: Context, private val tree: Uri) {
    private val cr: ContentResolver = context.contentResolver
    private val rootId = DocumentsContract.getTreeDocumentId(tree)

    init {
        val granted = cr.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
        // Renaming or moving the vault folder also removes the app's access to it.
        if (!granted || !exists(rootId)) throw VaultUnavailableException(
            "Vault folder not available (moved, renamed or access removed). Choose it again in the app")
    }

    /**
     * Writes a file to [folder] (path relative to the vault, e.g. "Inbox/Voice").
     * Returns the final file name, which may carry a " 2", " 3"… suffix if [baseName] was taken.
     */
    fun write(folder: String, baseName: String, ext: String, body: (OutputStream) -> Unit): String {
        val dirId = ensureDir(folder)
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(tree, dirId)
        val tmpName = ".capture-${UUID.randomUUID()}.tmp"
        val tmp = DocumentsContract.createDocument(cr, dirUri, "application/octet-stream", tmpName)
            ?: throw VaultUnavailableException("Cannot create files in $folder")
        try {
            val written = cr.openOutputStream(tmp, "w")?.use { out ->
                val counting = CountingOutput(out)
                body(counting)
                counting.flush()
                counting.count
            } ?: throw VaultUnavailableException("Cannot write to $folder")
            val onDisk = size(tmp)
            if (onDisk != null && onDisk != written) error("Write incomplete ($onDisk of $written bytes)")

            val name = uniqueName(dirId, baseName, ext)
            DocumentsContract.renameDocument(cr, tmp, name)
                ?: error("Rename failed")
            return name
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(cr, tmp) }
            throw t
        }
    }

    /**
     * Appends [text] to the end of the existing note at [path] (relative to the vault).
     * Nothing already in the note is rewritten. Returns false if the note no longer exists.
     */
    fun append(path: String, text: String): Boolean {
        val dirId = findPath(path.substringBeforeLast('/', "")) ?: return false
        val name = path.substringAfterLast('/')
        val docId = children(dirId).firstOrNull { it.second == name && !it.third }?.first ?: return false
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        cr.openOutputStream(uri, "wa")?.use { it.write(text.toByteArray()); it.flush() }
            ?: throw VaultUnavailableException("Cannot write to $name")
        return true
    }

    /** Removes temp files left behind by an interrupted write. */
    fun cleanupTemp(folder: String) {
        val dirId = findPath(folder) ?: return
        children(dirId).filter { it.second.startsWith(".capture-") && it.second.endsWith(".tmp") }.forEach {
            runCatching { DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, it.first)) }
        }
    }

    private fun uniqueName(dirId: String, base: String, ext: String): String {
        val taken = children(dirId).map { it.second.lowercase() }.toSet()
        var n = 1
        while (true) {
            val candidate = if (n == 1) "$base.$ext" else "$base $n.$ext"
            if (candidate.lowercase() !in taken) return candidate
            n++
        }
    }

    private fun ensureDir(path: String): String {
        var id = rootId
        for (part in path.split('/').filter { it.isNotBlank() }) {
            id = children(id).firstOrNull { it.second == part && it.third }?.first
                ?: DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, id),
                    Document.MIME_TYPE_DIR, part)?.let(DocumentsContract::getDocumentId)
                ?: throw VaultUnavailableException("Cannot create folder $part")
        }
        return id
    }

    private fun findPath(path: String): String? {
        var id = rootId
        for (part in path.split('/').filter { it.isNotBlank() }) {
            id = children(id).firstOrNull { it.second == part && it.third }?.first ?: return null
        }
        return id
    }

    /** (documentId, displayName, isDirectory) of each child. */
    private fun children(dirId: String): List<Triple<String, String, Boolean>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
        val proj = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        return cr.query(uri, proj, null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getString(2) == Document.MIME_TYPE_DIR)) }
        } ?: throw VaultUnavailableException("Vault folder cannot be read")
    }

    private fun exists(docId: String): Boolean = runCatching {
        cr.query(DocumentsContract.buildDocumentUriUsingTree(tree, docId), arrayOf(Document.COLUMN_DOCUMENT_ID),
            null, null, null)?.use { it.moveToFirst() } ?: false
    }.getOrDefault(false)

    private fun size(doc: Uri): Long? =
        cr.query(doc, arrayOf(Document.COLUMN_SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    private class CountingOutput(private val inner: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(b: Int) { inner.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { inner.write(b, off, len); count += len }
        override fun flush() = inner.flush()
    }
}
