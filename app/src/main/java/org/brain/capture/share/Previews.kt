package org.brain.capture.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import java.io.File

/** Readable app names and small previews of shared files, made on the phone. */
object Previews {
    private const val SIZE = 320

    /** "Files", "Firefox", … for a package name; null if Android doesn't reveal it. */
    fun appName(ctx: Context, pkg: String?): String? = pkg?.let {
        runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString()
        }.getOrNull()
    }

    data class Preview(val thumb: File?, val pages: Int?)

    /** Writes a JPEG thumbnail of an image or of a PDF's first page to [out]. Never throws. */
    fun make(file: File, mime: String?, out: File): Preview = runCatching {
        out.parentFile?.mkdirs()
        when {
            mime?.startsWith("image/") == true -> {
                val bmp = imageThumb(file) ?: return Preview(null, null)
                save(bmp, out); Preview(out, null)
            }
            mime == "application/pdf" -> ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { pdf ->
                    val pages = pdf.pageCount
                    if (pages == 0) return Preview(null, 0)
                    pdf.openPage(0).use { page ->
                        val w = SIZE
                        val h = (SIZE.toFloat() * page.height / page.width).toInt().coerceIn(1, SIZE * 2)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        save(bmp, out)
                    }
                    Preview(out, pages)
                }
            }
            else -> Preview(null, null)
        }
    }.getOrElse { Preview(null, null) }

    private fun imageThumb(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = runCatching {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        return if (rotation == 0f) bmp
        else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
    }

    private fun save(bmp: Bitmap, out: File) = out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
}
