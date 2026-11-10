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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        // Recording is independent of processing: a new thought can always be captured.
        if (CaptureState.isRecording) CaptureService.send(this, CaptureService.ACTION_STOP)
        else CaptureService.send(this, CaptureService.ACTION_START)
    }

    @Composable
    private fun Panel() {
        val phase by CaptureState.phase.collectAsState()
        val recording by CaptureState.recording.collectAsState()
        val level by CaptureState.level.collectAsState()
        val shown = remember { mutableStateOf(false) }
        var hadRecording by remember { mutableStateOf(false) }
        LaunchedEffect(recording) { if (recording != null) hadRecording = true }
        LaunchedEffect(Unit) { shown.value = true }
        // Close shortly after a clean save; failures and warnings stay until dismissed.
        LaunchedEffect(phase, recording) {
            val p = phase
            if (recording == null && hadRecording && p is Phase.Saved && p.warning == null) { delay(1500); finish() }
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
                    shadowElevation = 8.dp,
                ) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        val r = recording
                        val p = phase
                        when {
                            r != null -> {
                                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                                LaunchedEffect(r.startedAtMs) { while (true) { now = System.currentTimeMillis(); delay(200) } }
                                val s = ((now - r.startedAtMs) / 1000).coerceAtLeast(0)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    PulsingDot()
                                    Text("Recording", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                    Text("%d:%02d".format(s / 60, s % 60), fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                                }
                                LinearProgressIndicator(progress = { level }, color = brand().record,
                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                                        .semantics { contentDescription = "Input level" })
                                r.minutesLeft?.let {
                                    Text("Storage low: about $it min left", color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Button(
                                    onClick = { CaptureService.send(this@QuickRecordActivity, CaptureService.ACTION_STOP) },
                                    modifier = Modifier.fillMaxWidth().height(64.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = brand().record, contentColor = Color.White),
                                ) { Text("Stop", style = MaterialTheme.typography.titleMedium) }
                            }
                            !hadRecording && p !is Phase.Failed -> {
                                Text("Starting microphone…", style = MaterialTheme.typography.titleMedium)
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                            p is Phase.Saved -> {
                                Text(if (p.warning == null) "✓ Saved" else "Saved, with a warning",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (p.warning == null) brand().success else MaterialTheme.colorScheme.error)
                                Text(p.warning ?: p.notePath.substringBeforeLast('/'), style = MaterialTheme.typography.bodySmall)
                                if (p.warning != null) OutlinedButton(onClick = { finish() }, Modifier.fillMaxWidth()) { Text("Close") }
                            }
                            p is Phase.Failed -> {
                                Text("⚠ ${p.message}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                                if (p.wavPath != null) {
                                    Text("The recording is safe on the phone.", style = MaterialTheme.typography.bodySmall)
                                    Button(onClick = {
                                        startActivity(Intent(this@QuickRecordActivity, MainActivity::class.java)
                                            .putExtra(CaptureService.EXTRA_RETRY, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                        finish()
                                    }, Modifier.fillMaxWidth().height(56.dp)) { Text("Try again") }
                                } else OutlinedButton(onClick = { finish() }, Modifier.fillMaxWidth()) { Text("Close") }
                            }
                            else -> {
                                Text(phaseLabel(p) ?: "Saving recording…", style = MaterialTheme.typography.titleMedium)
                                if (p is Phase.Transcribing && p.total > 1)
                                    LinearProgressIndicator(progress = { p.done / p.total.toFloat() }, modifier = Modifier.fillMaxWidth())
                                else LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text("You can close this. It continues in the background.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
