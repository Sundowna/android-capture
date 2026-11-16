package org.brain.capture.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.brain.capture.R
import org.brain.capture.data.Capture
import org.brain.capture.data.CaptureDb
import org.brain.capture.data.CaptureTypes

/**
 * Screen 8 of the UX concepts as a home-screen widget: Idea, Task and Private start talking with
 * one tap (the capture screen opens and records at once); Open Brain opens the notes.
 * The last line says what happened to the latest capture.
 */
class BrainWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) = refresh(ctx)

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Redraws every Brain widget and the quick settings tile with the latest capture. */
        fun refresh(ctx: Context) {
            val app = ctx.applicationContext
            scope.launch {
                val last = runCatching { CaptureDb.get(app).dao().latest() }.getOrNull()
                val manager = AppWidgetManager.getInstance(app)
                val ids = manager.getAppWidgetIds(ComponentName(app, BrainWidget::class.java))
                if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(app, last))
                runCatching { TileService.requestListeningState(app, ComponentName(app, BrainTile::class.java)) }
            }
        }

        private fun views(ctx: Context, last: Capture?) = RemoteViews(ctx.packageName, R.layout.widget_brain).apply {
            fun capture(type: String, code: Int) = PendingIntent.getActivity(ctx, code,
                CaptureActivity.intent(ctx, type, start = true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            setOnClickPendingIntent(R.id.w_btn_idea, capture(CaptureTypes.IDEA, 11))
            setOnClickPendingIntent(R.id.w_btn_task, capture(CaptureTypes.TASK, 12))
            setOnClickPendingIntent(R.id.w_btn_private, capture(CaptureTypes.PRIVATE, 13))
            setOnClickPendingIntent(R.id.w_btn_open, PendingIntent.getActivity(ctx, 14,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
            setTextViewText(R.id.widget_last, last?.let { "Last capture: ${describe(it)} · ${status(it)}" } ?: "No captures yet")
        }

        /** "Idea · 0:48", "Task · typed", "Private · 1:12". */
        fun describe(c: Capture): String {
            val type = when (c.type) { CaptureTypes.TASK -> "Task"; CaptureTypes.PRIVATE -> "Private"; else -> "Idea" }
            val s = c.audioSeconds.toLong()
            return when (c.kind) {
                "voice" -> "$type · %d:%02d".format(s / 60, s % 60)
                "link" -> "$type · link"
                else -> "$type · typed"
            }
        }

        fun status(c: Capture): String = when {
            c.isPrivate -> "kept on this phone"
            c.clean != null -> "cleaned on phone"
            c.cleanup?.startsWith("rejected") == true -> "exact words kept"
            else -> "saved to vault"
        }
    }
}
