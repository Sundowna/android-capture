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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import org.brain.capture.CaptureState
import org.brain.capture.Phase
import org.brain.capture.Prefs
import org.brain.capture.R
import org.brain.capture.audio.CaptureService
import org.brain.capture.data.Capture
import org.brain.capture.data.CaptureDb
import org.brain.capture.pipeline.Notes
import org.brain.capture.transcribe.ModelStore
import java.io.File

class MainActivity : ComponentActivity() {

    private val retryRequest = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        retryRequest.value = intent.getBooleanExtra(CaptureService.EXTRA_RETRY, false)
        setContent { CaptureTheme { App(retryRequest) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(CaptureService.EXTRA_RETRY, false)) retryRequest.value = true
    }
}

@Composable
private fun App(retryRequest: MutableState<Boolean>) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var showSettings by remember { mutableStateOf(false) }
    var setupTick by remember { mutableIntStateOf(0) }
    val setupDone = remember(showSettings, setupTick) { prefs.vaultUri != null && ModelStore(ctx).isInstalled() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            !setupDone -> FirstRunScreen(onChanged = { setupTick++ })
            showSettings -> SettingsScreen(onBack = { showSettings = false })
            else -> HomeScreen(retryRequest, onSettings = { showSettings = true })
        }
    }
}

@Composable
private fun HomeScreen(retryRequest: MutableState<Boolean>, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val dao = remember { CaptureDb.get(ctx).dao() }
    val phase by CaptureState.phase.collectAsState()
    val recording by CaptureState.recording.collectAsState()
    val level by CaptureState.level.collectAsState()
    val live by CaptureState.live.collectAsState()
    var limit by remember { mutableIntStateOf(prefs.cardCount) }
    val cards by remember(limit) { dao.cards(limit) }.collectAsState(emptyList())
    val total by remember { dao.cardCount() }.collectAsState(0)
    val pendingRows by remember { dao.inProgress() }.collectAsState(emptyList())
    // Only recordings whose audio still exists, and not the one being recorded right now (the hero shows that).
    val pending = pendingRows.filter { c -> c.stagingPath?.let { File(it).exists() } == true && c.stagingPath != recording?.file }

    // Re-read notes when coming back to the app (e.g. after editing in Obsidian).
    var refresh by remember { mutableIntStateOf(0) }
    var resumedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LifecycleResumeEffect(Unit) { refresh++; resumedAt = System.currentTimeMillis(); onPauseOrDispose { } }
    LaunchedEffect(Unit) {
        Notes.importHistory(ctx); Notes.recoverEdits(ctx)
        org.brain.capture.share.Shares.resumePending(ctx) // shares that could not be saved earlier
        // Recordings left over from an interruption are finished automatically.
        if (!phase.isBusy && phase !is Phase.Failed && CaptureService.pendingRecordings(ctx).isNotEmpty())
            CaptureService.send(ctx, CaptureService.ACTION_PROCESS_PENDING)
    }
    LaunchedEffect(retryRequest.value) {
        if (retryRequest.value) { retryRequest.value = false; CaptureService.send(ctx, CaptureService.ACTION_PROCESS_PENDING) }
    }

    // "Saved" highlight on the card for a few seconds, plus a short confirmation with one useful action.
    val saved = phase as? Phase.Saved
    var highlightUntil by remember { mutableLongStateOf(0L) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(saved?.atMs) {
        val s = saved ?: return@LaunchedEffect
        if (s.atMs < resumedAt) return@LaunchedEffect // saved while the app was not visible
        highlightUntil = s.atMs + 5000
        val r = snackbar.showSnackbar(
            message = if (s.appended) "Added to the note" else "Saved to your vault",
            actionLabel = "Open in Obsidian", duration = SnackbarDuration.Short)
        if (r == SnackbarResult.ActionPerformed) openInObsidian(ctx, s.notePath)
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(highlightUntil) { while (System.currentTimeMillis() < highlightUntil) { delay(500); now = System.currentTimeMillis() }; now = System.currentTimeMillis() }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) CaptureService.send(ctx, CaptureService.ACTION_START)
    }
    fun toggleRecording(appendTo: Capture? = null) {
        when {
            recording != null -> CaptureService.send(ctx, CaptureService.ACTION_STOP)
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                CaptureService.send(ctx, CaptureService.ACTION_START) {
                    if (appendTo != null) {
                        putExtra(CaptureService.EXTRA_APPEND_TO, appendTo.id)
                        putExtra(CaptureService.EXTRA_APPEND_LABEL, appendTo.notePath)
                    }
                }
            else -> permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
        }
    }
    val canRecord = recording == null
    val listAlpha by animateFloatAsState(if (recording != null) 0.38f else 1f, label = "dim")

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item("top") {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Brain Capture", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (recording == null) IconButton(onClick = onSettings) {
                        Icon(painterResource(R.drawable.ic_tune), "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
                        PulsingDot()
                        Text("  REC", style = MaterialTheme.typography.labelLarge, color = brand().record)
                    }
                }
            }
            item("hero") {
                RecordHero(recording, level, enabled = true,
                    idleHint = if (pending.isNotEmpty()) "Tap to capture another thought" else "Tap to capture a thought",
                    onClick = { toggleRecording() })
            }
            // A general problem that is not tied to a recording (e.g. microphone or storage).
            val failed = phase as? Phase.Failed
            if (failed != null && failed.wavPath == null && recording == null) item("error") {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(failed.message, Modifier.weight(1f).padding(vertical = 14.dp), color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { CaptureState.set(Phase.Idle) }) { Text("OK") }
                    }
                }
            }
            if (pending.isNotEmpty() || cards.isNotEmpty()) item("label") {
                Text("RECENT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp).alpha(listAlpha))
            }
            items(pending, key = { "p-" + it.id }) { c ->
                Box(Modifier.animateItem().alpha(listAlpha)) {
                    ProgressCard(c, phase, live, CaptureState.processingId,
                        onRetry = { CaptureService.send(ctx, CaptureService.ACTION_PROCESS_PENDING) })
                }
            }
            items(cards, key = { it.id }) { c ->
                val justSaved = saved?.captureId == c.id && now < highlightUntil
                Box(Modifier.animateItem().alpha(listAlpha)) { NoteCard(c, refresh, justSaved, canRecord) { toggleRecording(it) } }
            }
            if (cards.isEmpty() && pending.isEmpty()) item("empty") {
                Text("Your notes will appear here. Record a thought, or share a link, text or file to Brain Capture from any app.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            }
            if (total > limit) item("more") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { limit += prefs.cardCount }) {
                        Text("Show ${minOf(prefs.cardCount, total - limit)} more", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/**
 * Opens [notePath] (relative to the chosen vault folder) in Obsidian.
 * Obsidian on Android needs the vault's name plus the path inside the vault; it ignores the
 * absolute-path form. If the chosen folder is the vault (it contains `.obsidian`), its folder
 * name is the vault name. Otherwise fall back to the absolute path.
 */
internal fun openInObsidian(ctx: android.content.Context, notePath: String): Boolean {
    val tree = Prefs(ctx).vaultUri ?: return false
    val docId = android.provider.DocumentsContract.getTreeDocumentId(tree) // e.g. "primary:Documents/Vault"
    val relRoot = docId.substringAfter(':', "")
    val file = notePath.removeSuffix(".md")
    val link = if (isVaultRoot(ctx, tree, docId)) {
        "obsidian://open?vault=" + Uri.encode(relRoot.substringAfterLast('/')) + "&file=" + Uri.encode(file)
    } else {
        val volume = docId.substringBefore(':')
        val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        "obsidian://open?path=" + Uri.encode(listOf(root, relRoot, notePath).filter { it.isNotEmpty() }.joinToString("/"))
    }
    return runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}

private fun isVaultRoot(ctx: android.content.Context, tree: Uri, docId: String): Boolean = runCatching {
    val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
    ctx.contentResolver.query(children,
        arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
        var found = false
        while (c.moveToNext()) if (c.getString(0) == ".obsidian") { found = true; break }
        found
    } ?: false
}.getOrDefault(false)

/** File picker that starts in the Download folder, where the browser saved the model. */
internal class OpenInDownloads : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: android.content.Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(
            android.provider.DocumentsContract.EXTRA_INITIAL_URI,
            android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download"),
        )
}
