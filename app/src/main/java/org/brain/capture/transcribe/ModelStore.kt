package org.brain.capture.transcribe

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * The Whisper model lives in app-private storage. The user downloads the ZIP with their browser
 * (the app has no internet permission) and imports it once. The import checks the file against
 * the published SHA-256, so a broken or tampered download is rejected.
 */
class ModelStore(private val context: Context) {
    val dir: File get() = File(context.filesDir, "whisper")

    fun isInstalled(): Boolean = WhisperOnnx.REQUIRED.all { File(dir, it).length() > 0 }

    /** Unpacks the .onnx files from [zip]. [progress] gets 0..1. */
    fun import(zip: Uri, progress: (Float?) -> Unit) {
        val size = context.contentResolver.query(zip, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        if (size != null && size != MODEL_SIZE) error(
            "This is not the expected model file (${size / 1_000_000} MB instead of ${MODEL_SIZE / 1_000_000} MB). " +
                "If the download was interrupted, download it again.")

        val tmp = File(context.filesDir, "whisper.importing").apply { deleteRecursively(); mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256")
        val hashing = HashingInput(context.contentResolver.openInputStream(zip)
            ?: error("Cannot open the selected file"), digest)
        try {
            ZipInputStream(hashing.buffered(1 shl 16)).use { z ->
                val buf = ByteArray(1 shl 16)
                var last = 0L
                while (true) {
                    val e = z.nextEntry ?: break
                    val name = e.name.substringAfterLast('/')
                    if (e.isDirectory || !name.endsWith(".onnx")) continue
                    File(tmp, name).outputStream().use { out ->
                        while (true) {
                            val n = z.read(buf); if (n < 0) break
                            out.write(buf, 0, n)
                            if (hashing.count - last > 1_000_000) {
                                last = hashing.count
                                progress((hashing.count.toFloat() / MODEL_SIZE).coerceAtMost(1f))
                            }
                        }
                    }
                }
                // Read to the very end so the checksum covers the whole file.
                hashing.drain()
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha != MODEL_SHA256) error("The file is damaged or not the official model (checksum mismatch). Download it again.")
            val missing = WhisperOnnx.REQUIRED.filter { !File(tmp, it).exists() }
            if (missing.isNotEmpty()) error("The model file is incomplete (missing ${missing.joinToString()}).")
        } catch (t: Throwable) {
            tmp.deleteRecursively()
            throw t
        }
        dir.deleteRecursively()
        check(tmp.renameTo(dir)) { "Could not install the model" }
        progress(1f)
    }

    private class HashingInput(private val inner: InputStream, private val md: MessageDigest) : InputStream() {
        var count = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) { md.update(it.toByte()); count++ } }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            inner.read(b, off, len).also { if (it > 0) { md.update(b, off, it); count += it } }
        fun drain() { val b = ByteArray(1 shl 16); while (read(b, 0, b.size) >= 0) {} }
        override fun close() = inner.close()
    }

    companion object {
        /** whisper_small_int8.zip from huggingface.co/DocWolle/whisperOnnx (Whisper+ model). */
        const val MODEL_URL = "https://huggingface.co/DocWolle/whisperOnnx/resolve/main/whisper_small_int8.zip?download=true"
        const val MODEL_SIZE = 242_722_488L
        const val MODEL_SHA256 = "97ac121610693e8c9adaef9c6db9066ef0cbebba53ca6e47f6d1541068ca19e3"
    }
}
