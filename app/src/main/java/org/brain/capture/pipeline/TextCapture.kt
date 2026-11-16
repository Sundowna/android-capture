package org.brain.capture.pipeline

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
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
    suspend fun save(ctx: Context, text: String, type: String): Capture = withContext(Dispatchers.IO) {
        val dao = CaptureDb.get(ctx).dao()
        if (type == CaptureTypes.PRIVATE) {
            val now = System.currentTimeMillis()
            return@withContext Capture(id = UUID.randomUUID().toString(), createdAt = now, state = CaptureStates.SAVED,
                kind = "text", transcript = text, type = type, cleanup = "skipped: typed").also {
                dao.upsert(it); org.brain.capture.ui.BrainWidget.refresh(ctx)
            }
        }
        val staged = Shares.stage(ctx, Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
            null) {}
        dao.setType(staged.id, type)
        Shares.save(ctx, staged.id)
        org.brain.capture.ui.BrainWidget.refresh(ctx)
        dao.get(staged.id) ?: error("Saved note not found")
    }
}
