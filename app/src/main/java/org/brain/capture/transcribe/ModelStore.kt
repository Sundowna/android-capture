package org.brain.capture.transcribe

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The Whisper model lives in app-private storage. It is imported once from the ZIP the user
 * picks (whisper_small_int8.zip from Hugging Face DocWolle/whisperOnnx), so the app never
 * needs internet access.
 */
class ModelStore(private val context: Context) {
    val dir: File get() = File(context.filesDir, "whisper")

    fun isInstalled(): Boolean = WhisperOnnx.REQUIRED.all { File(dir, it).length() > 0 }

    /** Unpacks the .onnx files from [zip]. [progress] gets 0..1 when the size is known, else null. */
    fun import(zip: Uri, progress: (Float?) -> Unit) {
        val total = context.contentResolver.query(zip, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        val tmp = File(context.filesDir, "whisper.importing").apply { deleteRecursively(); mkdirs() }

        val counting = CountingInput(context.contentResolver.openInputStream(zip)
            ?: error("Cannot open the selected file"))
        ZipInputStream(counting.buffered()).use { z ->
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
                        if (counting.count - last > 1_000_000) {
                            last = counting.count
                            progress(total?.let { (counting.count.toFloat() / it).coerceAtMost(1f) })
                        }
                    }
                }
            }
        }
        val missing = WhisperOnnx.REQUIRED.filter { !File(tmp, it).exists() }
        if (missing.isNotEmpty()) {
            tmp.deleteRecursively()
            error("This ZIP is not the Whisper ONNX model (missing ${missing.joinToString()})")
        }
        dir.deleteRecursively()
        check(tmp.renameTo(dir)) { "Could not install model" }
        progress(1f)
    }

    private class CountingInput(private val inner: java.io.InputStream) : java.io.InputStream() {
        var count = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count += it }
        override fun close() = inner.close()
    }
}
