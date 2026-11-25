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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.brain.capture.Prefs
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.brain.capture.Housekeeping
import org.brain.capture.transcribe.ModelStore

/** The pages of Settings, in menu order. */
private enum class Page(val title: String) {
    Types("Capture types"), Widget("Widget"), Vault("Vault"), Transcription("Transcription"),
    Display("Audio & display"), Storage("Storage"), About("About & privacy"),
}

/**
 * Settings: a short menu, one row per area with what is set now; each row opens its own page.
 * Back on a page returns to the menu, back on the menu leaves Settings.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    BackHandler { if (page != null) page = null else onBack() }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.padding(start = 8.dp, top = 8.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (page != null) page = null else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(page?.title ?: "Settings", style = MaterialTheme.typography.headlineSmall)
        }
        androidx.compose.animation.AnimatedContent(page, label = "settingsPage", transitionSpec = {
            val forward = targetState != null
            (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(260)) { if (forward) it / 6 else -it / 6}) togetherWith
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160))
        }) { p ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (p == null) Menu { page = it } else PageContent(p)
            }
        }
    }
}

@Composable
private fun Menu(open: (Page) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val profiles by remember { org.brain.capture.types.Profiles.flow(ctx) }.collectAsState()
    var storage by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { storage = withContext(Dispatchers.IO) { Housekeeping.usage(ctx).let { it.model + it.staging + it.thumbs + it.database } } }
    val types = profiles.orEmpty()
    val language = when (prefs.language) { "de" -> "Deutsch"; "en" -> "English"; else -> "Auto language" }
    val summary = mapOf(
        Page.Types to "${types.size} types · default ${org.brain.capture.types.Profiles.primary(ctx).name}",
        Page.Widget to types.filter { it.onWidget }.joinToString(", ") { it.name }.ifEmpty { "No buttons" },
        Page.Vault to listOfNotNull(prefs.vaultUri?.lastPathSegment?.substringAfter(':')?.substringAfterLast('/') ?: "Not selected", prefs.inboxFolder).joinToString(" · "),
        Page.Transcription to (if (ModelStore(ctx).isInstalled()) "Whisper small" else "Model not installed") + " · " + language +
            (if (!prefs.aiCleanup && org.brain.capture.cleanup.GemmaHolder.modelFile(ctx) != null) " · AI off" else ""),
        Page.Display to (if (prefs.audioFormat == "wav") "WAV" else "Opus") + " · ${prefs.cardCount} notes shown" +
            (if (prefs.shareAudio) "" else " · share without recording"),
        Page.Storage to (storage?.let { android.text.format.Formatter.formatShortFileSize(ctx, it) + " on this phone" } ?: "…"),
        Page.About to "No internet, no accounts",
    )
    // One panel, rows divided by hairlines.
    androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), color = Ink.Panel) {
        Column {
            Page.entries.forEachIndexed { i, p ->
                if (i > 0) androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 18.dp), color = Ink.Line)
                Row(Modifier.fillMaxWidth().clickable { open(p) }.heightIn(min = 64.dp).padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.titleMedium, color = Ink.Text)
                        Text(summary[p].orEmpty(), style = MaterialTheme.typography.bodySmall, color = Ink.TextDim,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    Text("›", fontSize = 24.sp, color = Ink.TextDim, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun PageContent(p: Page) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    when (p) {
        Page.Types -> CaptureTypesSettings(part = TypesPart.Types)
        Page.Widget -> CaptureTypesSettings(part = TypesPart.Widget)
        Page.Vault -> {
            VaultSetup()
            var inbox by remember { mutableStateOf(prefs.inboxFolder) }
            OutlinedTextField(
                inbox, { v -> inbox = v; v.trim('/', ' ').takeIf { it.isNotEmpty() }?.let { prefs.inboxFolder = it } },
                label = { Text("Inbox folder inside the vault") },
                supportingText = { Text("New notes go to $inbox/Voice, /Links, /Text, /Documents and /Attachments") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        Page.Transcription -> { ModelSetup(); LanguageSetup(); GemmaSetup() }
        Page.Display -> {
            Text("Audio in the vault", style = MaterialTheme.typography.titleSmall)
            Choice(listOf("opus" to "Opus (small)", "wav" to "WAV (lossless)"), prefs.audioFormat) { prefs.audioFormat = it }
            Hint("Opus is about an eighth of the size and clear for speech. Transcription always uses the full-quality recording.")
            Text("Notes on the home screen", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Choice(listOf(5 to "5", 10 to "10", 20 to "20"), prefs.cardCount) { prefs.cardCount = it }
            Hint("How many show before “Show more”.")
            Text("Sharing a note", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Choice(listOf(true to "With voice recording", false to "Without"), prefs.shareAudio) { prefs.shareAudio = it }
            Hint("What the share button on a card sends: the note's text and photos always, the voice recording only if you choose “With”.")
        }
        Page.Storage -> {
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
            Hint("Recordings and shares that are not in the vault yet are never deleted here.")
        }
        Page.About -> Text("This app has no internet permission. Recordings, transcripts and shared files stay on this phone " +
            "and in your vault folder; only your own sync tool moves them further. No accounts, no tracking.",
            style = MaterialTheme.typography.bodyMedium, color = Ink.TextSoft)
    }
}

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

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

/** The AI cleanup model (Gemma): import, replace or remove the .litertlm file. */
@Composable
fun GemmaSetup() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf(org.brain.capture.cleanup.GemmaHolder.modelFile(ctx)) }
    var progress by remember { mutableStateOf<Float?>(-1f) } // -1 = not importing
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    var askRemove by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(OpenInDownloads()) { uri: Uri? ->
        if (uri != null) scope.launch {
            error = null; done = null; progress = 0f
            runCatching { org.brain.capture.cleanup.GemmaHolder.import(ctx, uri) { p -> scope.launch { progress = p } } }
                .onSuccess { done = "Installed ✓ New notes are cleaned with it." }
                .onFailure { error = it.message ?: "The import failed." }
            progress = -1f
            file = org.brain.capture.cleanup.GemmaHolder.modelFile(ctx)
        }
    }
    val f = file
    SetupLine("AI cleanup model", if (f != null) "${f.name.removeSuffix(".litertlm")} · ${android.text.format.Formatter.formatShortFileSize(ctx, f.length())}"
        else "Not installed: notes keep your exact words") {
        if (f != null) OutlinedButton(enabled = progress == -1f, onClick = { pick.launch(arrayOf("*/*")) }) { Text("Replace") }
    }
    // Switch AI cleanup off without removing the model: then nothing is cleaned or loaded, and the
    // note screen drops its Clean tab and "Save with AI" (as without a model).
    if (f != null) {
        val prefs = remember { Prefs(ctx) }
        var aiOn by remember { mutableStateOf(prefs.aiCleanup) }
        SetupLine("AI cleanup", if (aiOn) "On: notes are tidied into bullets" else "Off: notes keep your exact words") {
            androidx.compose.material3.Switch(checked = aiOn, onCheckedChange = {
                aiOn = it; prefs.aiCleanup = it; done = null // the import message would contradict "Off"
                if (!it) scope.launch { org.brain.capture.cleanup.GemmaHolder.unload() }
            }, modifier = Modifier.semantics { contentDescription = "AI cleanup" })
        }
    }
    ModelAdvice(showDownloads = f == null) { error = it }
    if (f == null) {
        Text("Download it once with your browser, then import it. It runs on this phone; nothing goes online.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(enabled = progress == -1f, onClick = { pick.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Import model file") }
    } else if (progress == -1f) {
        androidx.compose.material3.TextButton(onClick = { askRemove = true }) { Text("Remove model", color = MaterialTheme.colorScheme.error) }
    }
    when (val p = progress) {
        -1f -> {}
        null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        else -> {
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            Text("Copying… ${(p * 100).toInt()} %", style = MaterialTheme.typography.bodySmall)
        }
    }
    done?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (askRemove) androidx.compose.material3.AlertDialog(
        onDismissRequest = { askRemove = false },
        title = { Text("Remove the AI model?") },
        text = { Text("New notes keep your exact words until you import a model again. The file you imported from stays where it is.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = {
            askRemove = false
            scope.launch { org.brain.capture.cleanup.GemmaHolder.remove(ctx); file = null; done = null }
        }) { Text("Remove model", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { askRemove = false }) { Text("Keep model") } },
    )
}

/**
 * What suits this phone (its name and memory) and, before a model is installed, the download links
 * from [org.brain.capture.cleanup.ModelCatalog]. Downloads open in the browser; the app stays offline.
 */
@Composable
private fun ModelAdvice(showDownloads: Boolean, onError: (String) -> Unit) {
    val ctx = LocalContext.current
    val catalog = org.brain.capture.cleanup.ModelCatalog
    val gb = remember {
        val info = android.app.ActivityManager.MemoryInfo()
        (ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(info)
        catalog.memoryGb(info.totalMem)
    }
    val best = catalog.recommended(gb)
    Text(catalog.advice(android.os.Build.MODEL, gb), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!showDownloads) return
    catalog.MODELS.forEach { m ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("${m.name} · ${m.size}", style = MaterialTheme.typography.bodyMedium)
                Text(if (m == best) "Suits this phone. ${m.note}" else m.note, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(m.url))) }
                    .onFailure { onError("No browser found to download the model.") }
            }) { Text("Download") }
        }
    }
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
