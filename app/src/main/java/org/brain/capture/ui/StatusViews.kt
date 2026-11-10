package org.brain.capture.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.brain.capture.Phase

@Composable
fun CaptureTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Status lines for every pipeline phase. Used by the app screen and the quick-record panel. */
@Composable
fun StatusContent(phase: Phase, level: Float) {
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        when (phase) {
            Phase.Idle -> Text("Ready. Tap the button to record.")
            is Phase.Recording -> {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(phase) { while (true) { now = System.currentTimeMillis(); delay(250) } }
                val s = ((now - phase.startedAtMs) / 1000).coerceAtLeast(0)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulsingDot()
                    Spacer(Modifier.width(10.dp))
                    Text("Recording  %d:%02d".format(s / 60, s % 60), style = MaterialTheme.typography.titleLarge)
                }
                phase.minutesLeft?.let {
                    Text("Storage low: about $it min of recording left", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                phase.appendTo?.let {
                    Text("Adding to: ${it.substringAfterLast('/').removeSuffix(".md")}",
                        style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth()
                    .semantics { contentDescription = "Input level" })
            }
            Phase.SavingAudio -> Busy("Saving recording…")
            Phase.LoadingModel -> Busy("Preparing transcription…")
            is Phase.Transcribing ->
                if (phase.total > 1) {
                    Text("Transcribing · part ${phase.done + 1} of ${phase.total}", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { phase.done / phase.total.toFloat() }, modifier = Modifier.fillMaxWidth())
                } else Busy("Transcribing…")
            Phase.WritingVault -> Busy("Saving to vault…")
            is Phase.Saved -> {
                Text(when {
                        phase.warning != null -> "Saved, with a warning"
                        phase.appended -> "✓ Added to note"
                        else -> "✓ Saved"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (phase.warning == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Text(phase.warning ?: phase.notePath, style = MaterialTheme.typography.bodySmall)
            }
            is Phase.Failed -> {
                Text("⚠ ${phase.message}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                if (phase.wavPath != null) Text("The recording is kept on the phone and nothing is lost.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Busy(label: String) {
    Text(label, style = MaterialTheme.typography.titleMedium)
    LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
fun PulsingDot() {
    val t = rememberInfiniteTransition(label = "pulse")
    val s by t.animateFloat(0.7f, 1.15f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse")
    Box(Modifier.size(14.dp).scale(s)) {
        Surface(Modifier.fillMaxSize(), shape = CircleShape, color = MaterialTheme.colorScheme.error) {}
    }
}

val Phase.isBusy: Boolean get() = when (this) {
    Phase.Idle, is Phase.Saved, is Phase.Failed -> false
    else -> true
}
