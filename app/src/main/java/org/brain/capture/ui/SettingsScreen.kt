package org.brain.capture.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.brain.capture.Prefs
import org.brain.capture.Housekeeping
import org.brain.capture.transcribe.ModelStore

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }

        Section("Vault") {
            VaultSetup()
            var inbox by remember { mutableStateOf(prefs.inboxFolder) }
            OutlinedTextField(
                inbox, { v -> inbox = v; v.trim('/', ' ').takeIf { it.isNotEmpty() }?.let { prefs.inboxFolder = it } },
                label = { Text("Inbox folder inside the vault") },
                supportingText = { Text("New notes go to $inbox/Voice, /Links, /Text, /Documents and /Attachments") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }

        Section("Transcription") {
            ModelSetup()
            LanguageSetup()
        }

        Section("Main screen") {
            Text("Notes shown before “Load more”", style = MaterialTheme.typography.bodyMedium)
            Choice(listOf(5 to "5", 10 to "10", 20 to "20"), prefs.cardCount) { prefs.cardCount = it }
        }

        Section("Audio in the vault") {
            Choice(listOf("opus" to "Opus (small)", "wav" to "WAV (lossless)"), prefs.audioFormat) { prefs.audioFormat = it }
            Text("Opus is about an eighth of the size and clear for speech. Transcription always uses the full-quality recording.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section("Storage on this phone") {
            var usage by remember { mutableStateOf<Housekeeping.Usage?>(null) }
            var cleaned by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(cleaned) { usage = withContext(Dispatchers.IO) { Housekeeping.usage(ctx) } }
            val u = usage
            if (u == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else {
                StorageLine("Speech model", u.model)
                StorageLine("Waiting recordings and shares", u.staging)
                StorageLine("Card previews", u.thumbs)
                StorageLine("Database", u.database)
            }
            OutlinedButton(onClick = {
                scope.launch { cleaned = withContext(Dispatchers.IO) { Housekeeping.clean(ctx) } }
            }) { Text("Clean up leftovers") }
            cleaned?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("Recordings and shares that are not in the vault yet are never deleted here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section("Privacy") {
            Text("This app has no internet permission. Recordings, transcripts and shared files stay on this phone " +
                "and in your vault folder; only your own sync tool moves them further. No accounts, no tracking.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun StorageLine(label: String, bytes: Long) {
    val ctx = LocalContext.current
    Row {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(android.text.format.Formatter.formatShortFileSize(ctx, bytes), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun <T> Choice(options: List<Pair<T, String>>, initial: T, onChange: (T) -> Unit) {
    var selected by remember { mutableStateOf(initial) }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = selected == value,
                onClick = { selected = value; onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label) }
        }
    }
}

/** Choosing the vault folder. Also used on the main screen until setup is done. */
@Composable
fun VaultSetup(onChanged: () -> Unit = {}) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var vault by remember { mutableStateOf(prefs.vaultUri) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            prefs.vaultUri = uri; vault = uri; onChanged()
        }
    }
    SetupLine("Vault folder", vault?.lastPathSegment?.substringAfter(':') ?: "Not selected") {
        OutlinedButton(onClick = { pick.launch(null) }) { Text(if (vault == null) "Choose" else "Change") }
    }
}

/** Installing the speech model (browser download + verified import). */
@Composable
fun ModelSetup(onChanged: () -> Unit = {}) {
    val ctx = LocalContext.current
    val models = remember { ModelStore(ctx) }
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(models.isInstalled()) }
    var progress by remember { mutableStateOf<Float?>(-1f) } // -1 = not importing
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(OpenInDownloads()) { uri: Uri? ->
        if (uri != null) scope.launch {
            error = null; progress = null
            runCatching { withContext(Dispatchers.IO) { models.import(uri) { p -> scope.launch { progress = p } } } }
                .onFailure { error = it.message }
            progress = -1f
            ready = models.isInstalled(); onChanged()
        }
    }
    val mimes = arrayOf("application/zip", "application/octet-stream")
    SetupLine("Speech model", if (ready) "Installed (Whisper small)" else "Not installed yet") {
        if (ready) OutlinedButton(enabled = progress == -1f, onClick = { pick.launch(mimes) }) { Text("Replace") }
    }
    if (!ready) {
        Text("The app never goes online. Download the model (243 MB) once with your browser, then import it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ModelStore.MODEL_URL))) }
                    .onFailure { error = "No browser found to download the model." }
            }, modifier = Modifier.weight(1f)) { Text("1. Download") }
            Button(enabled = progress == -1f, onClick = { pick.launch(mimes) }, modifier = Modifier.weight(1f)) { Text("2. Import") }
        }
    }
    when (val p = progress) {
        -1f -> {}
        null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        else -> LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
fun LanguageSetup() {
    val prefs = Prefs(LocalContext.current)
    Text("Language", style = MaterialTheme.typography.bodyMedium)
    Choice(listOf("auto" to "Auto", "de" to "Deutsch", "en" to "English"), prefs.language) { prefs.language = it }
}

@Composable
private fun SetupLine(label: String, value: String, action: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action()
    }
}
