package org.brain.capture.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.brain.capture.CaptureState
import org.brain.capture.Phase
import org.brain.capture.audio.CaptureService

/**
 * The red "Record" home-screen icon. Shows a small panel at the bottom of the screen:
 * first tap starts recording, Stop (or tapping the icon again) stops it, and the panel then
 * shows transcription progress and closes itself once the note is saved.
 * Tapping outside closes the panel; recording and processing carry on in the background.
 */
class QuickRecordActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish(); return
        }
        enableEdgeToEdge()
        if (savedInstanceState == null) toggle()
        setContent { CaptureTheme { Panel() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        toggle() // icon tapped again while the panel is open
    }

    private fun toggle() {
        val phase = CaptureState.phase.value
        when {
            phase is Phase.Recording -> CaptureService.send(this, CaptureService.ACTION_STOP)
            !phase.isBusy -> CaptureService.send(this, CaptureService.ACTION_START)
            else -> {} // still processing an earlier recording: just show its progress
        }
    }

    @androidx.compose.runtime.Composable
    private fun Panel() {
        val phase by CaptureState.phase.collectAsState()
        val level by CaptureState.level.collectAsState()
        val shown = remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown.value = true }
        // Close shortly after a clean save; failures and warnings stay until dismissed.
        LaunchedEffect(phase) {
            if (phase is Phase.Saved && (phase as Phase.Saved).warning == null) { delay(1500); finish() }
        }

        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                .clickable(remember { MutableInteractionSource() }, indication = null) { finish() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(shown.value, enter = slideInVertically { it }) {
                Surface(
                    Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding()
                        .clickable(remember { MutableInteractionSource() }, indication = null) {},
                    shape = RoundedCornerShape(28.dp),
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                ) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (phase is Phase.Idle) Text("Starting microphone…", style = MaterialTheme.typography.titleMedium)
                        else StatusContent(phase, level)
                        when {
                            phase is Phase.Recording -> Button(
                                onClick = { CaptureService.send(this@QuickRecordActivity, CaptureService.ACTION_STOP) },
                                modifier = Modifier.fillMaxWidth().height(64.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            ) { Text("Stop", style = MaterialTheme.typography.titleMedium) }
                            phase.isBusy -> Text("You can close this. It continues in the background.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            phase is Phase.Failed -> Button(onClick = {
                                startActivity(Intent(this@QuickRecordActivity, MainActivity::class.java)
                                    .putExtra(CaptureService.EXTRA_RETRY, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                finish()
                            }, Modifier.fillMaxWidth().height(56.dp)) { Text("Retry") }
                            else -> OutlinedButton(onClick = { finish() }, Modifier.fillMaxWidth()) { Text("Close") }
                        }
                    }
                }
            }
        }
    }
}
