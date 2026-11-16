package org.brain.capture.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.brain.capture.Phase
import org.brain.capture.R
import org.brain.capture.RecordingInfo

/**
 * The big record button. Idle: calm brand-coloured circle with a microphone.
 * Recording: red stop button inside rings that breathe with the voice, a large timer and a
 * live level meter.
 */
@Composable
fun RecordHero(recording: RecordingInfo?, level: Float, enabled: Boolean, idleHint: String, onDiscard: () -> Unit = {}, onClick: () -> Unit) {
    val b = brand()
    val isRec = recording != null
    val color by animateColorAsState(if (isRec) b.record else MaterialTheme.colorScheme.primary, tween(250), label = "c")
    val lvl by animateFloatAsState(if (isRec) level else 0f, spring(stiffness = 300f), label = "l")
    val ring by animateDpAsState(if (isRec) 228.dp else 132.dp, spring(dampingRatio = 0.7f, stiffness = 260f), label = "r")

    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(if (isRec) 228.dp else 150.dp), contentAlignment = Alignment.Center) {
            if (isRec) {
                Box(Modifier.size(ring).scale(0.86f + 0.14f * lvl).clip(CircleShape).background(b.record.copy(alpha = 0.07f)))
                Box(Modifier.size(ring * 0.81f).scale(0.92f + 0.08f * lvl).clip(CircleShape).background(b.record.copy(alpha = 0.11f)))
            }
            Surface(
                onClick = onClick, enabled = enabled, shape = CircleShape, color = color,
                shadowElevation = 6.dp,
                modifier = Modifier.size(132.dp).semantics { contentDescription = if (isRec) "Stop recording" else "Start recording" },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isRec) Box(Modifier.size(34.dp).clip(RoundedCornerShape(7.dp)).background(Color.White))
                    else Icon(painterResource(R.drawable.ic_mic), null, Modifier.size(46.dp),
                        tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        if (recording != null) {
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(recording.startedAtMs) { while (true) { now = System.currentTimeMillis(); delay(200) } }
            val s = ((now - recording.startedAtMs) / 1000).coerceAtLeast(0)
            Text("%d:%02d".format(s / 60, s % 60), fontSize = 52.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { contentDescription = "Recording time ${s / 60} minutes ${s % 60} seconds" })
            LevelBars(level, b.record)
            recording.appendLabel?.let {
                Text("Adding to: ${it.substringAfterLast('/').removeSuffix(".md")}",
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                    modifier = Modifier.padding(horizontal = 24.dp))
            }
            recording.minutesLeft?.let {
                Text("Storage low: about $it min of recording left", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error)
            }
            Text("Tap to stop · keeps recording when the screen is off", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            DiscardButton(recording.startedAtMs, onDiscard)
        } else {
            Text(idleHint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Last ~0.75 s of input level as bars; real values, not a decorative waveform. */
@Composable
private fun LevelBars(level: Float, color: Color) {
    val history = remember { mutableStateListOf<Float>().apply { repeat(15) { add(0f) } } }
    LaunchedEffect(level) { history.removeAt(0); history.add(level) }
    Row(Modifier.height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        history.forEach { v ->
            Box(Modifier.width(4.dp).height((6 + 34 * v).dp).clip(RoundedCornerShape(2.dp))
                .background(color.copy(alpha = 0.45f + 0.55f * v)))
        }
    }
}

@Composable
fun PulsingDot() {
    val t = rememberInfiniteTransition(label = "pulse")
    val s by t.animateFloat(0.7f, 1.15f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse")
    Box(Modifier.size(10.dp).scale(s).clip(CircleShape).background(brand().record))
}

/** Text for the processing state, used in the quick-record panel and in-progress cards. */
fun phaseLabel(p: Phase): String? = when (p) {
    Phase.LoadingModel -> "Preparing transcription…"
    is Phase.Transcribing -> buildString {
        append("Transcribing")
        if (p.total > 1) append(" · part ${p.done + 1} of ${p.total}")
    }
    Phase.WritingVault -> "Saving to vault…"
    else -> null
}

val Phase.isBusy: Boolean get() = this is Phase.LoadingModel || this is Phase.Transcribing || this is Phase.WritingVault

/**
 * Throws the running recording away. Short recordings go at once; from 20 s on it asks first,
 * so a mis-tap can't wipe a long thought.
 */
@Composable
fun DiscardButton(startedAtMs: Long, onDiscard: () -> Unit, modifier: Modifier = Modifier) {
    var asking by remember { mutableStateOf(false) }
    androidx.compose.material3.TextButton(modifier = modifier, onClick = {
        if (System.currentTimeMillis() - startedAtMs < 20_000) onDiscard() else asking = true
    }) { Text("Discard", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) }
    if (asking) androidx.compose.material3.AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text("Discard this recording?") },
        text = { Text("It will not be transcribed or saved, and can't be recovered.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { asking = false; onDiscard() }) {
            Text("Discard", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { asking = false }) { Text("Keep recording") } },
    )
}
