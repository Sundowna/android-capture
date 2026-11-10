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
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

/** Brand colours beyond the Material scheme. */
data class Brand(
    val record: Color, val success: Color, val successBg: Color,
    val voiceBg: Color, val voice: Color, val linkBg: Color, val link: Color, val fileBg: Color, val file: Color,
)

private val LightBrand = Brand(
    record = Color(0xFFD93F3F), success = Color(0xFF1E6B44), successBg = Color(0xFFE3F2EA),
    voiceBg = Color(0xFFECEAF8), voice = Color(0xFF4A3F9E), linkBg = Color(0xFFE6F0FB), link = Color(0xFF1F5FA8),
    fileBg = Color(0xFFF6EBDD), file = Color(0xFF8A4B12),
)
private val DarkBrand = Brand(
    record = Color(0xFFE5484D), success = Color(0xFF7FD3A5), successBg = Color(0xFF173A28),
    voiceBg = Color(0xFF2B2660), voice = Color(0xFFC7BFFF), linkBg = Color(0xFF14304F), link = Color(0xFF9CC6F5),
    fileBg = Color(0xFF3B2A17), file = Color(0xFFF0C08A),
)

@Composable
fun brand(): Brand = if (isSystemInDarkTheme()) DarkBrand else LightBrand

/** One calm, consistent look in light and dark mode (not wallpaper colours, so it always matches the icon). */
@Composable
fun CaptureTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFFB3A9FF), onPrimary = Color(0xFF1F1A4D),
        primaryContainer = Color(0xFF2E2866), onPrimaryContainer = Color(0xFFE4DFFF),
        background = Color(0xFF101216), onBackground = Color(0xFFE6E8EE),
        surface = Color(0xFF1A1D23), onSurface = Color(0xFFE6E8EE),
        surfaceVariant = Color(0xFF262A32), onSurfaceVariant = Color(0xFFA9AFBC),
        surfaceContainer = Color(0xFF1A1D23), surfaceContainerHigh = Color(0xFF22262D),
        outline = Color(0xFF444A55), error = Color(0xFFFF8A80), onError = Color(0xFF3B0907),
        inverseSurface = Color(0xFFE6E8EE), inverseOnSurface = Color(0xFF1A1D23), inversePrimary = Color(0xFF4A3F9E),
    ) else lightColorScheme(
        primary = Color(0xFF4A3F9E), onPrimary = Color.White,
        primaryContainer = Color(0xFFECEAF8), onPrimaryContainer = Color(0xFF2B2470),
        background = Color(0xFFF5F6F8), onBackground = Color(0xFF16181D),
        surface = Color.White, onSurface = Color(0xFF16181D),
        surfaceVariant = Color(0xFFEDEFF3), onSurfaceVariant = Color(0xFF5B6170),
        surfaceContainer = Color.White, surfaceContainerHigh = Color.White,
        outline = Color(0xFFC9CDD6), error = Color(0xFFB3261E), onError = Color.White,
        inverseSurface = Color(0xFF23262E), inverseOnSurface = Color.White, inversePrimary = Color(0xFFC9C2FF),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * The big record button. Idle: calm brand-coloured circle with a microphone.
 * Recording: red stop button inside rings that breathe with the voice, a large timer and a
 * live level meter.
 */
@Composable
fun RecordHero(recording: RecordingInfo?, level: Float, enabled: Boolean, idleHint: String, onClick: () -> Unit) {
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
