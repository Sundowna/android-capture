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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.brain.capture.CaptureState
import org.brain.capture.Phase
import org.brain.capture.Prefs
import org.brain.capture.HistoryEntry
import org.brain.capture.R
import org.brain.capture.TranscriptHistory
import org.brain.capture.audio.CaptureService
import org.brain.capture.transcribe.ModelStore

class MainActivity : ComponentActivity() {

    private val retryRequest = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        retryRequest.value = intent.getBooleanExtra(CaptureService.EXTRA_RETRY, false)
        setContent {
            CaptureTheme { Surface(Modifier.fillMaxSize()) { CaptureScreen(retryRequest) } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(CaptureService.EXTRA_RETRY, false)) retryRequest.value = true
    }
}

@Composable
private fun CaptureScreen(retryRequest: MutableState<Boolean>) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val models = remember { ModelStore(ctx) }
    val scope = rememberCoroutineScope()
    val phase by CaptureState.phase.collectAsState()
    val level by CaptureState.level.collectAsState()
    val history by TranscriptHistory.entries.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { TranscriptHistory.load(ctx) } }

    var vault by remember { mutableStateOf(prefs.vaultUri) }
    var modelReady by remember { mutableStateOf(models.isInstalled()) }
    var importProgress by remember { mutableStateOf<Float?>(-1f) } // -1 = not importing
    var importError by remember { mutableStateOf<String?>(null) }
    var language by remember { mutableStateOf(prefs.language) }
    var waiting by remember { mutableStateOf(CaptureService.pendingRecordings(ctx).size) }
    LaunchedEffect(phase) { waiting = CaptureService.pendingRecordings(ctx).size }

    fun processWaiting() = CaptureService.send(ctx, CaptureService.ACTION_PROCESS_PENDING)

    // Recordings left over from an interruption (app killed, phone restarted) are finished
    // automatically when the app opens. After a failure they wait for Retry instead.
    LaunchedEffect(Unit) {
        if (!phase.isBusy && phase !is Phase.Failed && waiting > 0) processWaiting()
    }
    LaunchedEffect(retryRequest.value) {
        if (retryRequest.value) { retryRequest.value = false; processWaiting() }
    }

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
    val pickModel = rememberLauncherForActivityResult(OpenInDownloads()) { uri: Uri? ->
        if (uri != null) scope.launch {
            importError = null; importProgress = null
            runCatching {
                withContext(Dispatchers.IO) { models.import(uri) { p -> scope.launch { importProgress = p } } }
            }.onFailure { importError = it.message }
            importProgress = -1f
            modelReady = models.isInstalled()
        }
    }

    fun toggleRecording(appendTo: String? = null) {
        when {
            phase is Phase.Recording -> CaptureService.send(ctx, CaptureService.ACTION_STOP)
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                CaptureService.send(ctx, CaptureService.ACTION_START) {
                    if (appendTo != null) putExtra(CaptureService.EXTRA_APPEND_TO, appendTo)
                }
            else -> permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
        }
    }
    val canAppend = !phase.isBusy
    val onAppend: (HistoryEntry) -> Unit = { e -> e.notePath?.let { toggleRecording(it) } }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Brain Capture", style = MaterialTheme.typography.headlineMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp).heightIn(min = 72.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusContent(phase, level)
                if (phase.isBusy && waiting > 0) Text(
                    if (waiting == 1) "1 more recording is queued and will follow."
                    else "$waiting more recordings are queued and will follow.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (phase is Phase.Failed && waiting > 0) {
                    Text(
                        if (waiting == 1) "1 recording is not in the vault yet." else "$waiting recordings are not in the vault yet.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FilledTonalButton(onClick = ::processWaiting) { Text("Retry") }
                }
            }
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Button(
                onClick = { toggleRecording() },
                enabled = phase is Phase.Recording || !phase.isBusy,
                shape = CircleShape,
                modifier = Modifier.size(112.dp).semantics {
                    contentDescription = if (phase is Phase.Recording) "Stop recording" else "Start recording"
                },
                colors = if (phase is Phase.Recording) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.buttonColors(),
            ) {
                if (phase is Phase.Recording) Text("Stop", style = MaterialTheme.typography.titleMedium)
                else Icon(painterResource(R.drawable.ic_mic), contentDescription = null, Modifier.size(48.dp))
            }
        }

        history.firstOrNull()?.let { TranscriptCard(it, "Last transcript", canAppend, onAppend) }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Setup", style = MaterialTheme.typography.titleSmall)

                SetupRow("Vault folder", vault?.lastPathSegment?.substringAfter(':') ?: "Not selected") {
                    OutlinedButton(onClick = { pickVault.launch(null) }) { Text(if (vault == null) "Choose" else "Change") }
                }

                SetupRow("Speech model", if (modelReady) "Installed" else "Not installed yet") {
                    if (modelReady) OutlinedButton(enabled = importProgress == -1f,
                        onClick = { pickModel.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Replace") }
                }
                if (!modelReady) {
                    Text(
                        "The app never goes online. Download the model (243 MB) once with your browser, then import it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            runCatching {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ModelStore.MODEL_URL)))
                            }.onFailure { importError = "No browser found to download the model." }
                        }, modifier = Modifier.weight(1f)) { Text("1. Download") }
                        Button(enabled = importProgress == -1f,
                            onClick = { pickModel.launch(arrayOf("application/zip", "application/octet-stream")) },
                            modifier = Modifier.weight(1f)) { Text("2. Import") }
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

        val older = history.drop(1)
        if (older.isNotEmpty()) {
            Text("Earlier transcripts", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp))
            older.forEach { TranscriptCard(it, null, canAppend, onAppend) }
        }
    }
}

@Composable
private fun TranscriptCard(entry: HistoryEntry, title: String?, canAppend: Boolean, onAppend: (HistoryEntry) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            SelectionContainer { Text(entry.transcript, Modifier.padding(end = 8.dp)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "%s · %.0f s audio · load %.1f s · transcription %.1f s · %.2f× · %d part(s)".format(
                        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                            .format(java.util.Date(entry.atMs)),
                        entry.audioSeconds, entry.modelLoadMs / 1000.0, entry.transcribeMs / 1000.0,
                        entry.realTimeFactor, entry.chunks),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (entry.notePath != null) IconButton(onClick = {
                    if (!openInObsidian(ctx, entry.notePath)) android.widget.Toast.makeText(ctx,
                        "Could not open Obsidian. Is it installed?", android.widget.Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "Open note in Obsidian")
                }
                if (entry.notePath != null) IconButton(onClick = { onAppend(entry) }, enabled = canAppend,
                    modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_mic_add), contentDescription = "Record more for this note")
                }
                IconButton(onClick = {
                    // Only the transcript text, none of the timing details.
                    ctx.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Transcript", entry.transcript))
                    copied = true
                    scope.launch { kotlinx.coroutines.delay(1500); copied = false }
                }, modifier = Modifier.size(48.dp)) {
                    if (copied) Icon(Icons.Default.Check, contentDescription = "Copied",
                        tint = MaterialTheme.colorScheme.primary)
                    else Icon(painterResource(R.drawable.ic_copy), contentDescription = "Copy transcript")
                }
            }
        }
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

/**
 * Opens [notePath] (relative to the chosen vault folder) in Obsidian. Uses Obsidian's `path`
 * parameter with the absolute file path, so it works whether the chosen folder is the vault
 * itself or a folder inside it.
 */
private fun openInObsidian(ctx: android.content.Context, notePath: String): Boolean {
    val tree = Prefs(ctx).vaultUri ?: return false
    val docId = android.provider.DocumentsContract.getTreeDocumentId(tree) // e.g. "primary:Documents/Vault"
    val volume = docId.substringBefore(':')
    val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
    val absolute = listOf(root, docId.substringAfter(':', ""), notePath).filter { it.isNotEmpty() }.joinToString("/")
    val uri = Uri.parse("obsidian://open?path=" + Uri.encode(absolute))
    return runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}

/** File picker that starts in the Download folder, where the browser saved the model. */
private class OpenInDownloads : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: android.content.Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(
            android.provider.DocumentsContract.EXTRA_INITIAL_URI,
            android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download"),
        )
}
