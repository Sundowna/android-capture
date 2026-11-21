package org.brain.capture.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.brain.capture.R
import org.brain.capture.types.Profiles

/**
 * 1x1 home-screen widget: one capture type, one tap starts talking. Add as many as you like, each
 * with its own type (chosen when it is placed; long-press → Edit widget changes it).
 */
class ButtonWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(ctx, it)) }
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        val e = prefs(ctx).edit()
        ids.forEach { e.remove(key(it)) }
        e.apply()
    }

    companion object {
        private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("button_widgets", Context.MODE_PRIVATE)
        private fun key(id: Int) = "type_$id"

        fun typeOf(ctx: Context, id: Int): String = prefs(ctx).getString(key(id), null) ?: Profiles.primary(ctx).id
        fun setType(ctx: Context, id: Int, type: String) = prefs(ctx).edit().putString(key(id), type).apply()

        /** Redraws every 1x1 widget (after a type was renamed, re-iconed or deleted). */
        fun refreshAll(ctx: Context) {
            val m = AppWidgetManager.getInstance(ctx)
            m.getAppWidgetIds(ComponentName(ctx, ButtonWidget::class.java)).forEach { m.updateAppWidget(it, views(ctx, it)) }
        }

        fun views(ctx: Context, id: Int) = RemoteViews(ctx.packageName, R.layout.widget_button).apply {
            if (typeOf(ctx, id) == Profiles.PHOTO) {
                // Photo: the phone's camera, then the share panel (add to a note, voice comment).
                setInt(R.id.wb_root, "setBackgroundResource", R.drawable.w_amber)
                setImageViewResource(R.id.wb_icon, R.drawable.t_camera)
                setInt(R.id.wb_icon, "setColorFilter", 0xFF2A2118.toInt())
                setTextViewText(R.id.wb_label, "Photo")
                setTextColor(R.id.wb_label, 0xFF2A2118.toInt())
                setContentDescription(R.id.wb_root, "Take a photo")
                setOnClickPendingIntent(R.id.wb_root, PendingIntent.getActivity(ctx, 1000 + id, PhotoActivity.intent(ctx),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                return@apply
            }
            val p = Profiles.get(ctx, typeOf(ctx, id))
            setInt(R.id.wb_root, "setBackgroundResource", if (p.private) R.drawable.w_panel else R.drawable.w_amber)
            setImageViewResource(R.id.wb_icon, typeIcon(p.icon))
            val fg = if (p.private) 0xFFEEF0F2.toInt() else 0xFF2A2118.toInt()
            setInt(R.id.wb_icon, "setColorFilter", fg)
            setTextViewText(R.id.wb_label, p.name)
            setTextColor(R.id.wb_label, fg)
            setContentDescription(R.id.wb_root, "${p.name}, start talking")
            setOnClickPendingIntent(R.id.wb_root, PendingIntent.getActivity(ctx, 1000 + id,
                CaptureActivity.intent(ctx, p.id, start = true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
    }
}

/** Shown when a 1x1 widget is placed (and from Edit widget): pick its capture type. */
class ButtonWidgetSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val current = ButtonWidget.typeOf(this, id)
        setContent {
            CaptureTheme {
                Box(Modifier.fillMaxSize().background(Ink.Ground)) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("One-tap button", fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = Ink.Text)
                        Text("Which type should this button start? One tap on it starts talking; Photo opens the camera.", fontSize = 13.sp, color = Ink.TextDim)
                        (Profiles.all(this@ButtonWidgetSetupActivity) + Profiles.PHOTO_SLOT).forEach { p ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (p.id == current) Ink.Raised else Ink.Panel)
                                .clickable {
                                    ButtonWidget.setType(this@ButtonWidgetSetupActivity, id, p.id)
                                    AppWidgetManager.getInstance(this@ButtonWidgetSetupActivity)
                                        .updateAppWidget(id, ButtonWidget.views(this@ButtonWidgetSetupActivity, id))
                                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                                    finish()
                                }.heightIn(min = 60.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.size(40.dp).clip(CircleShape).background(if (p.private) Ink.Well else Ink.Amber), contentAlignment = Alignment.Center) {
                                    Icon(painterResource(typeIcon(p.icon)), null, Modifier.size(20.dp), tint = if (p.private) Ink.TextStrong else Ink.OnAmber)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink.Text)
                                    if (p.subtitle.isNotBlank()) Text(p.subtitle, fontSize = 12.sp, color = Ink.TextDim)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
