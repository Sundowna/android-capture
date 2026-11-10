package org.brain.capture.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.brain.capture.CaptureState
import org.brain.capture.Phase
import org.brain.capture.Prefs
import org.brain.capture.R
import org.brain.capture.audio.CaptureService
import org.brain.capture.transcribe.ModelStore
import java.io.File

class MainActivity : ComponentActivity() {

    private val retryRequest = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        retryRequest.value = intent.getStringExtra(CaptureService.EXTRA_WAV)
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) {
                Surface(Modifier.fillMaxSize()) { CaptureScreen(retryRequest) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        retryRequest.value = intent.getStringExtra(CaptureService.EXTRA_WAV)
    }
}

@Composable
private fun CaptureScreen(retryRequest: androidx.compose.runtime.MutableState<String?>) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val models = remember { ModelStore(ctx) }
    val scope = rememberCoroutineScope()
    val phase by CaptureState.phase.collectAsState()
    val level by CaptureState.level.collectAsState()
    val last by CaptureState.last.collectAsState()

    var vault by remember { mutableStateOf(prefs.vaultUri) }
    var modelReady by remember { mutableStateOf(models.isInstalled()) }
    var importProgress by remember { mutableStateOf<Float?>(-1f) } // -1 = not importing
    var importError by remember { mutableStateOf<String?>(null) }
    var language by remember { mutableStateOf(prefs.language) }
    var pending by remember { mutableStateOf(pendingRecordings(ctx)) }
    LaunchedEffect(phase) { pending = pendingRecordings(ctx) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) CaptureService.send(ctx, CaptureService.ACTION_START)
    }
    val pickVault = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            prefs.vaultUri = uri; vault = uri
        }
    }
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            importError = null; importProgress = null
            runCatching {
                withContext(Dispatchers.IO) { models.import(uri) { p -> scope.launch { importProgress = p } } }
            }.onFailure { importError = it.message }
            importProgress = -1f
            modelReady = models.isInstalled()
        }
    }

    // A Retry tapped in the notification arrives here, because only a visible app may restart the service.
    LaunchedEffect(retryRequest.value) {
        retryRequest.value?.let { path ->
            CaptureService.send(ctx, CaptureService.ACTION_RETRY) { putExtra(CaptureService.EXTRA_WAV, path) }
            retryRequest.value = null
        }
    }

    fun toggleRecording() {
        when {
            CaptureState.isRecording -> CaptureService.send(ctx, CaptureService.ACTION_STOP)
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                CaptureService.send(ctx, CaptureService.ACTION_START)
            else -> permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Brain Capture", style = MaterialTheme.typography.headlineMedium)

        StatusCard(phase, level)

        Box(Modifier.fillMaxWidth(), Alignment.Center) {
            RecordButton(
                recording = phase is Phase.Recording,
                enabled = phase is Phase.Recording || !CaptureState.isBusy,
                onClick = ::toggleRecording,
            )
        }

        last?.let { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Last transcript", style = MaterialTheme.typography.titleSmall)
                    SelectionContainer { Text(r.transcript.ifBlank { "(no speech recognised)" }) }
                    Text(
                        "Audio %.1f s · model load %.1f s · transcription %.1f s · %.2f× real time · %d part(s)"
                            .format(r.audioSeconds, r.modelLoadMs / 1000.0, r.transcribeMs / 1000.0, r.realTimeFactor, r.chunks),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (pending.isNotEmpty() && !CaptureState.isBusy) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Recordings waiting (${pending.size})", style = MaterialTheme.typography.titleSmall)
                    Text("These are kept safely on the phone and were not saved to the vault yet.",
                        style = MaterialTheme.typography.bodySmall)
                    pending.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(f.nameWithoutExtension, Modifier.weight(1f))
                            FilledTonalButton(onClick = {
                                CaptureService.send(ctx, CaptureService.ACTION_RETRY) { putExtra(CaptureService.EXTRA_WAV, f.path) }
                            }) { Text("Retry") }
                        }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Setup", style = MaterialTheme.typography.titleSmall)

                SetupRow("Vault folder", vault?.lastPathSegment?.substringAfter(':') ?: "Not selected") {
                    OutlinedButton(onClick = { pickVault.launch(null) }) { Text(if (vault == null) "Choose" else "Change") }
                }

                SetupRow("Whisper model", if (modelReady) "Installed" else "Not imported") {
                    OutlinedButton(enabled = importProgress == -1f, onClick = { pickModel.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                        Text(if (modelReady) "Replace" else "Import ZIP")
                    }
                }
                when (val p = importProgress) {
                    -1f -> {}
                    null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                }
                importError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                Text("Language", style = MaterialTheme.typography.bodyMedium)
                val options = listOf("auto" to "Auto", "de" to "Deutsch", "en" to "English")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { i, (code, label) ->
                        SegmentedButton(
                            selected = language == code,
                            onClick = { language = code; prefs.language = code },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) { Text(label) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(phase: Phase, level: Float) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).height(72.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (phase) {
                Phase.Idle -> Text("Ready. Tap the button to record.")
                is Phase.Recording -> {
                    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(phase) { while (true) { now = System.currentTimeMillis(); delay(250) } }
                    val s = ((now - phase.startedAtMs) / 1000).coerceAtLeast(0)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulsingDot()
                        Spacer(Modifier.width(8.dp))
                        Text("Recording  %d:%02d".format(s / 60, s % 60), style = MaterialTheme.typography.titleMedium)
                    }
                    LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth()
                        .semantics { contentDescription = "Input level" })
                }
                Phase.SavingAudio -> Busy("Saving recording…")
                Phase.LoadingModel -> Busy("Preparing transcription…")
                is Phase.Transcribing ->
                    if (phase.total > 1) {
                        Text("Transcribing · part ${phase.done + 1} of ${phase.total}")
                        LinearProgressIndicator(progress = { phase.done / phase.total.toFloat() }, modifier = Modifier.fillMaxWidth())
                    } else Busy("Transcribing…")
                Phase.WritingVault -> Busy("Saving to vault…")
                is Phase.Saved -> Text("✓ Saved to ${phase.notePath}", color = MaterialTheme.colorScheme.primary)
                is Phase.Failed -> {
                    Text("⚠ ${phase.message}", color = MaterialTheme.colorScheme.error)
                    if (phase.wavPath != null) Text("The recording is kept. Use Retry below.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun Busy(label: String) {
    Text(label)
    LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
private fun PulsingDot() {
    val t = rememberInfiniteTransition(label = "pulse")
    val s by t.animateFloat(0.7f, 1.1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse")
    Box(Modifier.size(12.dp).scale(s)) {
        Surface(Modifier.fillMaxSize(), shape = CircleShape, color = MaterialTheme.colorScheme.error) {}
    }
}

@Composable
private fun RecordButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        modifier = Modifier.size(112.dp).semantics { contentDescription = if (recording) "Stop recording" else "Start recording" },
        colors = if (recording) androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error) else androidx.compose.material3.ButtonDefaults.buttonColors(),
    ) {
        if (recording) Text("Stop", style = MaterialTheme.typography.titleMedium)
        else Icon(painterResource(R.drawable.ic_mic), contentDescription = null, Modifier.size(48.dp))
    }
}

@Composable
private fun SetupRow(label: String, value: String, action: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action()
    }
}

private fun pendingRecordings(ctx: android.content.Context): List<File> =
    CaptureService.stagingDir(ctx).listFiles { f -> f.extension == "wav" && f.length() > 44 }
        ?.sortedBy { it.name }.orEmpty()

@Composable
private fun Box(modifier: Modifier, alignment: Alignment, content: @Composable () -> Unit) =
    androidx.compose.foundation.layout.Box(modifier, contentAlignment = alignment) { content() }

@Composable
private fun Box(modifier: Modifier, content: @Composable () -> Unit) =
    androidx.compose.foundation.layout.Box(modifier) { content() }
