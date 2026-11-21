package org.brain.capture.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.brain.capture.R
import org.brain.capture.data.Capture
import org.brain.capture.data.CaptureDb
import org.brain.capture.types.Profiles

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
                runCatching { ButtonWidget.refreshAll(app) } // 1x1 buttons follow renamed or re-iconed types
                runCatching { TileService.requestListeningState(app, ComponentName(app, BrainTile::class.java)) }
            }
        }

        private val SLOT = intArrayOf(R.id.w_slot1, R.id.w_slot2, R.id.w_slot3, R.id.w_slot4)
        private val ICON = intArrayOf(R.id.w_icon1, R.id.w_icon2, R.id.w_icon3, R.id.w_icon4)
        private val TITLE = intArrayOf(R.id.w_title1, R.id.w_title2, R.id.w_title3, R.id.w_title4)
        private val SUB = intArrayOf(R.id.w_sub1, R.id.w_sub2, R.id.w_sub3, R.id.w_sub4)

        /** Fills the slots from Settings → Capture types: the first one amber, then the rest, then Open Brain. */
        private fun views(ctx: Context, last: Capture?) = RemoteViews(ctx.packageName, R.layout.widget_brain).apply {
            val slots = Profiles.widgetSlots(ctx)
            for (i in 0 until Profiles.WIDGET_SLOTS) {
                val shown = i < slots.size
                setViewVisibility(SLOT[i], if (shown) View.VISIBLE else View.GONE)
                if (!shown) continue
                val p = slots[i]
                val first = i == 0 && p != null
                setInt(SLOT[i], "setBackgroundResource", if (first) R.drawable.w_amber else R.drawable.w_panel)
                setImageViewResource(ICON[i], if (p == null) R.drawable.t_open else typeIcon(p.icon))
                setInt(ICON[i], "setColorFilter", if (first) 0xFF2A2118.toInt() else 0xFFEEF0F2.toInt())
                setTextViewText(TITLE[i], p?.name ?: "Open Brain")
                setTextViewText(SUB[i], p?.subtitle ?: "notes and recent captures")
                setTextColor(TITLE[i], if (first) 0xFF2A2118.toInt() else 0xFFEEF0F2.toInt())
                setTextColor(SUB[i], if (first) 0xD92A2118.toInt() else 0xFFC3C8CF.toInt())
                val photo = p?.id == Profiles.PHOTO
                setContentDescription(SLOT[i], when { p == null -> "Open Brain"; photo -> "Take a photo"; else -> "${p.name}, start talking" })
                setOnClickPendingIntent(SLOT[i], when {
                    p == null -> PendingIntent.getActivity(ctx, 30, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
                    photo -> PendingIntent.getActivity(ctx, 31, PhotoActivity.intent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    else -> PendingIntent.getActivity(ctx, 20 + i, CaptureActivity.intent(ctx, p.id, start = true),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                })
            }
            // Gaps and the second row only where there are buttons on both sides.
            setViewVisibility(R.id.w_gap1, if (slots.size >= 2) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.w_gap2, if (slots.size >= 4) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.w_row2, if (slots.size > 2) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.w_rowgap, if (slots.size > 2) View.VISIBLE else View.GONE)
            setTextViewText(R.id.widget_last, last?.let { "Last capture: ${describe(ctx, it)} · ${status(it)}" } ?: "No captures yet")
        }

        /** "Idea · 0:48", "Task · typed", "Private · 1:12", with the type's own name. */
        fun describe(ctx: Context, c: Capture): String {
            val type = Profiles.get(ctx, c.type).name
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
