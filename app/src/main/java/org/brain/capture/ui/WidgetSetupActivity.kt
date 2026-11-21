package org.brain.capture.ui

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Long-press the Brain widget → "Edit widget": choose its buttons and edit the capture types.
 * Also shown when a widget is placed, but optional: Done or Back both keep the widget.
 */
class WidgetSetupActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // The widget stays even if this screen is left with Back.
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        setContent {
            CaptureTheme {
                Box(Modifier.fillMaxSize().background(Ink.Ground)) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Widget", fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = Ink.Text)
                        CaptureTypesSettings(widgetOnly = true)
                        Surface(onClick = { done() }, shape = RoundedCornerShape(999.dp), color = Ink.Amber,
                            modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("Done", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink.OnAmber)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun done() {
        BrainWidget.refresh(this)
        finish()
    }

    override fun onDestroy() {
        BrainWidget.refresh(applicationContext)
        super.onDestroy()
    }
}
