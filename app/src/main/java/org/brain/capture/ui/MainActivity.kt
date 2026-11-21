package org.brain.capture.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.brain.capture.RecordingInfo
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
import androidx.compose.foundation.layout.height
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
import kotlinx.coroutines.launch
import org.brain.capture.pipeline.Undo
import androidx.compose.runtime.rememberCoroutineScope
import org.brain.capture.CaptureState
import org.brain.capture.Phase
import org.brain.capture.Prefs
import org.brain.capture.R
import org.brain.capture.audio.CaptureService
import org.brain.capture.data.Capture
import org.brain.capture.data.CaptureDb
import org.brain.capture.pipeline.Notes
import org.brain.capture.transcribe.ModelStore
import org.brain.capture.types.Profiles
import java.io.File

class MainActivity : ComponentActivity() {

    private val retryRequest = mutableStateOf(false)
    /** Bumped when the capture screen's chevron asks for the notes list. */
    private val homeRequest = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        retryRequest.value = intent.getBooleanExtra(CaptureService.EXTRA_RETRY, false)
        if (intent.getBooleanExtra(EXTRA_HOME, false)) homeRequest.intValue = 1
        setContent { CaptureTheme { App(retryRequest, homeRequest) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(CaptureService.EXTRA_RETRY, false)) retryRequest.value = true
        if (intent.getBooleanExtra(EXTRA_HOME, false)) homeRequest.intValue++
    }

    companion object {
        /** Open on the notes list (not a recording or settings): the capture screen's chevron. */
        const val EXTRA_HOME = "home"
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun App(retryRequest: MutableState<Boolean>, homeRequest: androidx.compose.runtime.MutableIntState) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var showSettings by remember { mutableStateOf(false) }
    var setupTick by remember { mutableIntStateOf(0) }
    val setupDone = remember(showSettings, setupTick) { prefs.vaultUri != null && ModelStore(ctx).isInstalled() }
    // The capture view grows out of this screen (no second screen sliding in). Null = the notes list.
    var capture by remember { mutableStateOf<CaptureRequest?>(null) }
    val recording by CaptureState.recording.collectAsState()
    // Opening the app while something records shows that recording.
    LaunchedEffect(Unit) { if (CaptureState.isRecording && homeRequest.intValue == 0) capture = CaptureRequest() }
    // Arriving from the capture screen's chevron: show the notes list.
    LaunchedEffect(homeRequest.intValue) { if (homeRequest.intValue > 0) { capture = null; showSettings = false } }
    BackHandler(enabled = capture != null) { capture = null }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            !setupDone -> FirstRunScreen(onChanged = { setupTick++ })
            showSettings -> SettingsScreen(onBack = { showSettings = false })
            else -> SharedTransitionLayout {
                AnimatedContent(capture, label = "flow",
                    contentKey = { it != null },
                    transitionSpec = { fadeIn(tween(420, delayMillis = 120, easing = FastOutSlowInEasing)) togetherWith
                        fadeOut(tween(260, easing = FastOutSlowInEasing)) }) { req ->
                    // The big amber button is one element: it glides between its place on the list and in the capture view.
                    val shared = Modifier.sharedElement(rememberSharedContentState("record"), this@AnimatedContent,
                        boundsTransform = { _, _ -> tween(650, easing = FastOutSlowInEasing) })
                    if (req != null) CaptureScreen(req, onClose = { capture = null }, buttonModifier = shared,
                        cardModifier = Modifier.animateEnterExit(
                            enter = fadeIn(tween(480, 160, FastOutSlowInEasing)) +
                                slideInVertically(tween(620, 80, FastOutSlowInEasing)) { it / 5 },
                            exit = fadeOut(tween(220))))
                    else HomeScreen(retryRequest, recording, shared,
                        onSettings = { showSettings = true },
                        onCapture = { capture = it })
                }
            }
        }
    }
}

@android.annotation.SuppressLint("ModifierParameter") // the shared big button's modifier, not the screen's
@Composable
private fun HomeScreen(
    retryRequest: MutableState<Boolean>, recording: RecordingInfo?, buttonModifier: Modifier,
    onSettings: () -> Unit, onCapture: (CaptureRequest) -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val dao = remember { CaptureDb.get(ctx).dao() }
    val phase by CaptureState.phase.collectAsState()
    // Read the voice level only where it is drawn, so the list does not redraw 20 times a second.
    val levelState = CaptureState.level.collectAsState()
    val level = remember { { levelState.value } }
    val live by CaptureState.live.collectAsState()
    var limit by remember { mutableIntStateOf(prefs.cardCount) }
    val allCards by remember(limit) { dao.cards(limit) }.collectAsState(emptyList())
    // Cards swiped away are hidden while their Undo bar shows; the note is deleted after that.
    val swiped by SwipeDeletes.hidden.collectAsState()
    val cards = allCards.filter { it.id !in swiped }
    val snackbar = remember { SnackbarHostState() }
    val snackScope = rememberCoroutineScope()
    // The home screen's Undo: the last action of any kind (Undo.kt).
    val undo by Undo.last.collectAsState()
    var undoing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Undo.cleanupOnStart(ctx) }
    fun swipeDelete(c: org.brain.capture.data.Capture) {
        SwipeDeletes.start(ctx, c)
        snackScope.launch {
            snackbar.currentSnackbarData?.dismiss() // a newer swipe replaces the bar; the older delete goes ahead
            // The bar stays just as long as Undo can still stop the delete.
            val r = kotlinx.coroutines.withTimeoutOrNull(SwipeDeletes.UNDO_MS - 300) {
                snackbar.showSnackbar(if (c.isPrivate) "Private note deleted" else "Note moved to the vault's .trash",
                    actionLabel = "Undo", duration = SnackbarDuration.Indefinite)
            }
            if (r == SnackbarResult.ActionPerformed) SwipeDeletes.undo(c.id)
        }
    }
    // Photo button: "Saving photo…" while it is copied to the vault, then its new note opens.
    var photoSaving by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val takePhoto = rememberTakePhoto(
        onSaving = { photoSaving = snackScope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar("Saving photo…", duration = SnackbarDuration.Indefinite) } },
        onDone = { id, message ->
            photoSaving?.cancel(); photoSaving = null // cancelling takes the "Saving photo…" bar away
            if (id != null) onCapture(CaptureRequest(noteId = id))
            else if (message != null) snackScope.launch { snackbar.showSnackbar(message, duration = SnackbarDuration.Long) }
        })
    var attachOpen by remember { mutableStateOf(false) }
    AttachSheet(attachOpen, appendTo = null, onDismiss = { attachOpen = false },
        onSaving = { photoSaving = snackScope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar("Saving…", duration = SnackbarDuration.Indefinite) } },
        onDone = { id, message ->
            photoSaving?.cancel(); photoSaving = null
            if (id != null) onCapture(CaptureRequest(noteId = id))
            else if (message != null) snackScope.launch { snackbar.showSnackbar(message, duration = SnackbarDuration.Long) }
        })
    val total by remember { dao.cardCount() }.collectAsState(0)
    val pendingRows by remember { dao.inProgress() }.collectAsState(emptyList())
    // Only recordings whose audio still exists, and not the one being recorded right now.
    val pending = pendingRows.filter { c -> c.stagingPath?.let { File(it).exists() } == true && c.stagingPath != recording?.file }

    // Re-read notes when coming back to the app (e.g. after editing in Obsidian).
    var refresh by remember { mutableIntStateOf(0) }
    var resumedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LifecycleResumeEffect(Unit) { refresh++; resumedAt = System.currentTimeMillis(); onPauseOrDispose { } }
    LaunchedEffect(Unit) {
        Notes.importHistory(ctx); Notes.recoverEdits(ctx)
        Shortcuts.update(ctx) // long-press the app icon: one shortcut per capture type
        org.brain.capture.share.Shares.resumePending(ctx) // shares that could not be saved earlier
        // Recordings left over from an interruption are finished automatically.
        if (!phase.isBusy && phase !is Phase.Failed && CaptureService.pendingRecordings(ctx).isNotEmpty())
            CaptureService.send(ctx, CaptureService.ACTION_PROCESS_PENDING)
    }
    LaunchedEffect(retryRequest.value) {
        if (retryRequest.value) { retryRequest.value = false; CaptureService.send(ctx, CaptureService.ACTION_PROCESS_PENDING) }
    }

    // A short highlight on the card that just changed.
    val saved = phase as? Phase.Saved
    var highlightUntil by remember { mutableLongStateOf(0L) }
    LaunchedEffect(saved?.atMs) { val s = saved ?: return@LaunchedEffect; if (s.atMs >= resumedAt) highlightUntil = s.atMs + 5000 }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(highlightUntil) { while (System.currentTimeMillis() < highlightUntil) { delay(500); now = System.currentTimeMillis() }; now = System.currentTimeMillis() }

    // The hero (big button + time) folds into the small bar at the top as the list scrolls.
    val list = rememberLazyListState()
    // Compose's default fling stops sooner than other Android lists; carry it further.
    val baseFling = androidx.compose.foundation.gestures.ScrollableDefaults.flingBehavior()
    val fling = remember(baseFling) { object : androidx.compose.foundation.gestures.FlingBehavior {
        override suspend fun androidx.compose.foundation.gestures.ScrollScope.performFling(initialVelocity: Float): Float =
            with(baseFling) { performFling(initialVelocity * FLING_BOOST) } / FLING_BOOST
    } }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val heroPx = with(density) { 220.dp.toPx() }
    val collapse by remember { derivedStateOf {
        if (list.firstVisibleItemIndex > 1) 1f else if (list.firstVisibleItemIndex == 1) (list.firstVisibleItemScrollOffset / heroPx).coerceIn(0f, 1f)
        else 0f
    } }
    val barShown = recording != null && collapse > 0.35f
    val canRecord = recording == null

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().safeDrawingPadding(), state = list, flingBehavior = fling,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item("top") {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Brain", fontFamily = Grotesk, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    // The last action, ready to take back; says what it will undo, gone when there is nothing.
                    AnimatedVisibility(undo != null && !undoing, enter = fadeIn() + androidx.compose.animation.expandHorizontally(),
                        exit = fadeOut() + androidx.compose.animation.shrinkHorizontally()) {
                        val label = remember(undo) { undo?.label ?: "" }
                        Surface(onClick = {
                            undoing = true
                            snackScope.launch {
                                val msg = Undo.undo(ctx)
                                undoing = false
                                snackbar.currentSnackbarData?.dismiss()
                                snackbar.showSnackbar(msg, duration = SnackbarDuration.Short)
                            }
                        }, shape = RoundedCornerShape(999.dp), color = Ink.Panel,
                            modifier = Modifier.height(40.dp).semantics { contentDescription = label }) {
                            Row(Modifier.padding(start = 12.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(painterResource(R.drawable.ic_undo), null, Modifier.size(18.dp), tint = Ink.Amber)
                                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.TextSoft)
                            }
                        }
                    }
                    IconButton(onClick = onSettings) {
                        Icon(painterResource(R.drawable.ic_tune), "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item("hero") {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp).graphicsLayer {
                    // Shrinks and fades toward the bar as it scrolls away.
                    val f = if (recording != null) collapse else 0f
                    alpha = 1f - f; scaleX = 1f - 0.35f * f; scaleY = 1f - 0.35f * f
                }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton(recording != null, recording?.paused == true, level, 120.dp, buttonModifier) {
                        val r = recording
                        if (r == null) onCapture(CaptureRequest(start = true, type = Profiles.primary(ctx).id))
                        else stopAndShow(ctx, r, onCapture)
                    }
                    if (recording != null) {
                        Elapsed(recording, 28.sp)
                        Text(if (recording.appendLabel != null) "Adding to a note · tap to see it" else "Recording · tap to see it",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else Text("Tap to talk · nothing to decide now", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item("modes") {
                // The same buttons as on the note screen (plus Type); each starts a new note.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BarButton("Type", R.drawable.ic_type, enabled = canRecord, modifier = Modifier.weight(1f)) {
                        onCapture(CaptureRequest(mode = CaptureActivity.MODE_TYPE, type = Profiles.primary(ctx).id)) }
                    // Pasted text usually needs no AI: Home's Paste starts as "Exact words" (if that type still exists).
                    BarButton("Paste", R.drawable.ic_paste, enabled = canRecord, modifier = Modifier.weight(1f)) {
                        val exact = Profiles.all(ctx).firstOrNull { it.id == "c-exact" }?.id
                        onCapture(CaptureRequest(mode = CaptureActivity.MODE_PASTE, type = exact ?: Profiles.primary(ctx).id)) }
                    // Photo: the phone's camera app, then a new photo note opens (talk or type to add words).
                    BarButton("Photo", R.drawable.t_camera, enabled = canRecord, modifier = Modifier.weight(1f)) { takePhoto(null) }
                    // Attach: Gallery, Files, Contact, Link or Location; then the new note opens.
                    BarButton("Attach", R.drawable.ic_attach, enabled = canRecord, modifier = Modifier.weight(1f)) { attachOpen = true }
                }
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
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            }
            items(pending, key = { "p-" + it.id }) { c ->
                Box(Modifier.animateItem()) {
                    ProgressCard(c, phase, live, CaptureState.processingId,
                        onRetry = { CaptureService.send(ctx, CaptureService.ACTION_PROCESS_PENDING) })
                }
            }
            items(cards, key = { it.id }) { c ->
                val justSaved = saved?.captureId == c.id && now < highlightUntil
                Box(Modifier.animateItem()) {
                    SwipeToDelete(onDelete = { swipeDelete(c) }) {
                        NoteCard(c, refresh, justSaved, canRecord,
                            onRecordMore = { onCapture(CaptureRequest(start = true, noteId = it.id)) },
                            onOpen = { onCapture(CaptureRequest(noteId = it.id)) },
                            onType = { onCapture(CaptureRequest(mode = CaptureActivity.MODE_TYPE, noteId = it.id)) },
                            onEdit = { onCapture(CaptureRequest(mode = CaptureActivity.MODE_EDIT, noteId = it.id)) })
                    }
                }
            }
            if (cards.isEmpty() && pending.isEmpty()) item("empty") {
                Text("Your notes will appear here. Talk a thought, type one, or share a link, text or file to Brain from any app.",
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

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            androidx.compose.material3.Snackbar(it, shape = RoundedCornerShape(14.dp), containerColor = Ink.Raised,
                contentColor = Ink.Text, actionColor = Ink.Amber)
        }

        // The small recording bar: slides down from the top once the big button has scrolled away.
        AnimatedVisibility(barShown, Modifier.align(Alignment.TopCenter),
            enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) { -it } + fadeIn(),
            exit = slideOutVertically(tween(180)) { -it } + fadeOut(tween(180))) {
            recording?.let { r -> RecordingBar(r, level, onOpen = { onCapture(CaptureRequest()) },
                onStop = { stopAndShow(ctx, r, onCapture) }) }
        }
    }
}

/** Stop means stop: the recording ends at once, then its note opens and fills in as it is processed. */
private fun stopAndShow(ctx: android.content.Context, r: RecordingInfo, onCapture: (CaptureRequest) -> Unit) {
    CaptureService.send(ctx, CaptureService.ACTION_STOP)
    onCapture(CaptureRequest(noteId = r.appendTo ?: r.captureId))
}

@Composable
private fun Elapsed(r: RecordingInfo, size: androidx.compose.ui.unit.TextUnit) {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(r.startedAtMs) { while (true) { tick = System.currentTimeMillis(); delay(250) } }
    @Suppress("UNUSED_EXPRESSION") tick
    val s = r.elapsedMs() / 1000
    Text("%d:%02d".format(s / 60, s % 60), fontFamily = Mono, fontSize = size, color = Ink.Text,
        modifier = Modifier.semantics { contentDescription = "Recording time ${s / 60} minutes ${s % 60} seconds" })
}

/** Top bar while recording: pulsing level lines, "Recording" and the time; tap to open, square to stop. */
@Composable
private fun RecordingBar(r: RecordingInfo, level: () -> Float, onOpen: () -> Unit, onStop: () -> Unit) {
    Surface(onClick = onOpen, color = Ink.Raised, shape = RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp),
        shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.statusBarsPadding().padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MiniLevel(level, active = !r.paused)
            Text(if (r.paused) "Paused" else if (r.appendLabel != null) "Adding to note" else "Recording",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink.TextStrong, modifier = Modifier.weight(1f))
            Elapsed(r, 14.sp)
            Surface(onClick = onStop, shape = CircleShape, color = Ink.Amber,
                modifier = Modifier.size(44.dp).semantics { contentDescription = "Stop and save" }) {
                Box(contentAlignment = Alignment.Center) {
                    Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(Ink.OnAmber))
                }
            }
        }
    }
}

/** Five little lines that pulse with the voice. */
@Composable
private fun MiniLevel(level: () -> Float, active: Boolean) {
    val t = rememberInfiniteTransition(label = "mini")
    val phase = t.animateFloat(0f, 6.28f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "p")
    val amber = Ink.Amber
    // Drawn on a canvas; the animation and the level are only read while drawing (no recomposition).
    androidx.compose.foundation.Canvas(Modifier.width(27.dp).height(22.dp)) {
        val v = if (active) level() else 0f
        val w = 3.dp.toPx(); val gap = 3.dp.toPx(); val r = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
        for (i in 0 until 5) {
            val wobble = if (active) (kotlin.math.sin(phase.value + i * 1.1f) + 1f) / 2f else 0f
            val h = (5 + 9 * wobble + 8 * v).dp.toPx()
            drawRoundRect(amber, topLeft = androidx.compose.ui.geometry.Offset(i * (w + gap), (size.height - h) / 2f),
                size = androidx.compose.ui.geometry.Size(w, h), cornerRadius = r)
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

/** How much further a fling carries the home list than Compose's default. */
private const val FLING_BOOST = 1.6f
