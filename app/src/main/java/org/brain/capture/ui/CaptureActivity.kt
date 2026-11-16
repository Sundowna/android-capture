package org.brain.capture.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.brain.capture.CaptureState
import org.brain.capture.R
import org.brain.capture.audio.CaptureService
import org.brain.capture.cleanup.CleanupStep
import org.brain.capture.cleanup.CleanupText
import org.brain.capture.cleanup.GemmaHolder
import org.brain.capture.data.Capture
import org.brain.capture.data.CaptureDb
import org.brain.capture.data.CaptureStates
import org.brain.capture.data.CaptureTypes
import org.brain.capture.pipeline.Notes
import org.brain.capture.pipeline.TextCapture

/**
 * Screen 5 of the UX concepts: the capture screen. Opens straight into capture (from the widget,
 * the quick settings tile or the app), never asks where things go, and shows the exact words the
 * moment Whisper has them and the clean version as soon as Gemma is done. Closing it keeps the
 * recording going; a saved capture can be undone.
 */
class CaptureActivity : ComponentActivity() {
    private val request = mutableStateOf<Request?>(null)

    /** What the widget, tile or app asked for: a mode, a type, and whether to start right away. */
    data class Request(val mode: String, val type: String, val start: Boolean, val at: Long = System.nanoTime())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) request.value = intent.toRequest()
        setContent { CaptureTheme { CaptureScreen(request.value, onClose = { finish() }) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        request.value = intent.toRequest()
    }

    private fun Intent.toRequest() = Request(
        mode = getStringExtra(EXTRA_MODE) ?: MODE_VOICE,
        type = getStringExtra(CaptureService.EXTRA_TYPE) ?: CaptureTypes.IDEA,
        start = getBooleanExtra(EXTRA_START, false),
    )

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_START = "start"
        const val MODE_VOICE = "voice"
        const val MODE_TYPE = "type"
        const val MODE_PASTE = "paste"

        fun intent(ctx: Context, type: String = CaptureTypes.IDEA, start: Boolean = true, mode: String = MODE_VOICE): Intent =
            Intent(ctx, CaptureActivity::class.java)
                .putExtra(CaptureService.EXTRA_TYPE, type).putExtra(EXTRA_START, start).putExtra(EXTRA_MODE, mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

private enum class View { Clean, Raw }

@Composable
private fun CaptureScreen(request: CaptureActivity.Request?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { CaptureDb.get(ctx).dao() }
    val recording by CaptureState.recording.collectAsState()
    val live by CaptureState.live.collectAsState()
    val level by CaptureState.level.collectAsState()
    val cleaningId by CleanupStep.cleaning.collectAsState()

    var mode by rememberSaveable { mutableStateOf(request?.mode ?: CaptureActivity.MODE_VOICE) }
    var kind by rememberSaveable { mutableStateOf(request?.type?.takeIf { it != CaptureTypes.PRIVATE } ?: CaptureTypes.IDEA) }
    var private by rememberSaveable { mutableStateOf(request?.type == CaptureTypes.PRIVATE) }
    val type = if (private) CaptureTypes.PRIVATE else kind
    var followId by rememberSaveable { mutableStateOf<String?>(recording?.captureId) }
    var view by rememberSaveable { mutableStateOf(View.Clean) }
    var userPickedView by rememberSaveable { mutableStateOf(false) }
    var undone by remember { mutableStateOf(false) }

    // Follow whatever is being recorded now; keep showing it after it stops.
    LaunchedEffect(recording?.captureId) { recording?.captureId?.let { followId = it; undone = false; userPickedView = false; view = View.Clean } }
    val followedFlow = remember(followId) { followId?.let { dao.watch(it) } }
    val followed by (followedFlow ?: kotlinx.coroutines.flow.flowOf(null)).collectAsState(null)
    val isRecordingThis = recording != null && recording?.captureId == followId

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) startVoice(ctx, type)
    }
    fun start() {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoice(ctx, type)
        else permission.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
    }
    // A widget or tile tap: switch to what it asked for and start talking at once.
    LaunchedEffect(request?.at) {
        val r = request ?: return@LaunchedEffect
        mode = r.mode
        if (r.type == CaptureTypes.PRIVATE) private = true else { kind = r.type; private = false }
        if (r.start && r.mode == CaptureActivity.MODE_VOICE && recording == null) start()
    }
    // Switching Open/Private or Idea/Task while recording changes where this capture goes.
    LaunchedEffect(type, followId) {
        val id = followId ?: return@LaunchedEffect
        val c = dao.get(id) ?: return@LaunchedEffect
        if (c.state != CaptureStates.SAVED && c.type != type) dao.setType(id, type)
    }

    // Which view to show: clean once it exists, the exact words while there is no clean version.
    val clean = followed?.clean
    val cleanupDone = followed?.cleanup != null
    val cleanupFailed = cleanupDone && clean == null
    LaunchedEffect(clean, cleanupFailed) { if (!userPickedView) view = if (cleanupFailed) View.Raw else View.Clean }

    Box(Modifier.fillMaxSize().background(Ink.Ground)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            TopBar(private, onPrivate = { private = it }, onClose = onClose)

            if (mode == CaptureActivity.MODE_VOICE) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ViewTab("Clean", if (private) "on this phone" else "via AI", view == View.Clean) { view = View.Clean; userPickedView = true }
                    ViewTab("Exactly what I said", "instant via Whisper", view == View.Raw) { view = View.Raw; userPickedView = true }
                    Spacer(Modifier.weight(1f))
                    if (!private) KindChip(kind) { kind = if (kind == CaptureTypes.TASK) CaptureTypes.IDEA else CaptureTypes.TASK }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (mode == CaptureActivity.MODE_TYPE) "Type a thought" else "Paste from clipboard",
                        fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = Ink.Text,
                        modifier = Modifier.weight(1f))
                    if (!private) KindChip(kind) { kind = if (kind == CaptureTypes.TASK) CaptureTypes.IDEA else CaptureTypes.TASK }
                }
            }

            // The card: what was said, clean or exact.
            Surface(Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(18.dp), color = Ink.Panel) {
                when (mode) {
                    CaptureActivity.MODE_VOICE -> VoiceCard(
                        followed = if (undone) null else followed, view = view, recordingThis = isRecordingThis,
                        paused = recording?.paused == true, liveText = live?.takeIf { it.captureId == followId }?.text,
                        cleaning = cleaningId != null && cleaningId == followId, gemmaInstalled = remember { GemmaHolder.modelFile(ctx) != null },
                        private = private, undone = undone,
                    )
                    else -> TextCard(mode, private, kind, onSaved = { c -> followId = c.id; undone = false })
                }
            }

            if (mode == CaptureActivity.MODE_VOICE) {
                Waveform(level, active = isRecordingThis && recording?.paused != true)
                Controls(
                    recordingThis = isRecordingThis, paused = recording?.paused == true,
                    elapsedMs = { recording?.elapsedMs() ?: ((followed?.audioSeconds ?: 0.0) * 1000).toLong() },
                    canUndo = !isRecordingThis && followed?.state == CaptureStates.SAVED && !undone,
                    onRecord = { if (recording == null) start() else CaptureService.send(ctx, CaptureService.ACTION_STOP) },
                    onPause = { CaptureService.send(ctx, if (recording?.paused == true) CaptureService.ACTION_RESUME else CaptureService.ACTION_PAUSE) },
                    onDiscard = { CaptureService.send(ctx, CaptureService.ACTION_CANCEL); followId = null },
                    onUndo = {
                        val c = followed ?: return@Controls
                        undone = true
                        scope.launch { runCatching { Notes.delete(ctx, c) } }
                    },
                )
            }

            ModeRow(mode, enabled = recording == null) { mode = it }
        }
    }
}

private fun startVoice(ctx: Context, type: String) {
    if (CaptureState.isRecording) return
    CaptureService.send(ctx, CaptureService.ACTION_START) { putExtra(CaptureService.EXTRA_TYPE, type) }
}

@Composable
private fun TopBar(private: Boolean, onPrivate: (Boolean) -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Surface(onClick = onClose, shape = RoundedCornerShape(12.dp), color = Ink.Panel,
            modifier = Modifier.size(44.dp).semantics { contentDescription = "Close, keep going in the background" }) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_close), null, Modifier.size(18.dp), tint = Ink.TextSoft)
            }
        }
        Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Ink.Well).padding(3.dp)
            .semantics { contentDescription = "Zone" }) {
            Segment("Open", !private) { onPrivate(false) }
            Segment("Private", private) { onPrivate(true) }
        }
    }
}

@Composable
private fun Segment(label: String, on: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) Ink.Raised else Color.Transparent, label = "seg")
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(bg).clickable(onClick = onClick)
        .semantics { selected = on; role = Role.Tab }.padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) Ink.TextStrong else Ink.TextDim)
    }
}

@Composable
private fun ViewTab(label: String, sub: String, on: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) Ink.Amber else Color.Transparent, label = "tab")
    val fg = if (on) Ink.OnAmber else Ink.TextSoft
    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(bg)
        .border(BorderStroke(1.dp, if (on) Ink.Amber else Ink.Line), RoundedCornerShape(16.dp))
        .clickable(onClick = onClick).semantics { selected = on; role = Role.Tab }
        .heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = fg, lineHeight = 16.sp)
        Text(sub, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = fg.copy(alpha = 0.85f), lineHeight = 14.sp)
    }
}

/** Idea or Task: the instruction Gemma follows for this capture. A tap switches; nothing to decide up front. */
@Composable
private fun KindChip(kind: String, onToggle: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onToggle)
        .semantics { contentDescription = "Capture type ${if (kind == CaptureTypes.TASK) "Task" else "Idea"}, tap to switch" }
        .heightIn(min = 44.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(if (kind == CaptureTypes.TASK) "Task ⇄" else "Idea ⇄", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ink.TextDim)
    }
}

@Composable
private fun VoiceCard(
    followed: Capture?, view: View, recordingThis: Boolean, paused: Boolean, liveText: String?,
    cleaning: Boolean, gemmaInstalled: Boolean, private: Boolean, undone: Boolean,
) {
    val transcript = followed?.transcript
    val clean = followed?.clean
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AnimatedContent(view to (clean != null), transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
            modifier = Modifier.weight(1f), label = "card") { (v, _) ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    undone -> Hint("Undone", "The capture moved to the vault's .trash folder (private ones are deleted). Tap the button to talk again.")
                    followed == null -> Hint("Tap to talk", "Say it however it comes. Brain files it for you; nothing to decide now.")
                    recordingThis -> Hint(if (paused) "Paused" else "Listening…",
                        if (v == View.Clean) "Talk freely. The clean version appears when you stop."
                        else "Your exact words appear the moment you stop.")
                    v == View.Raw -> {
                        val words = transcript ?: liveText
                        if (words.isNullOrBlank()) Hint("Transcribing…", "Whisper is writing down your words, on this phone.")
                        else Text(words, fontSize = 15.sp, lineHeight = 24.sp, color = Ink.TextBody)
                    }
                    clean != null -> CleanView(clean, transcript.orEmpty())
                    followed.cleanup != null -> Hint("Kept your exact words",
                        "The clean version didn't pass the check, so nothing was changed. See \"Exactly what I said\".")
                    transcript == null -> Hint("Transcribing…", "Clean notes follow as soon as Whisper is done.")
                    transcript.isBlank() -> Hint("No speech recognised", "The recording is kept with the note.")
                    !gemmaInstalled -> Hint("No cleanup model", "Your exact words are saved. Add a Gemma model to get a clean version.")
                    else -> Cleaning(cleaning)
                }
            }
        }
        Footer(followed, view, clean != null, recordingThis, private, undone)
    }
}

@Composable
private fun Hint(title: String, text: String) {
    Text(title, fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp, color = Ink.Text)
    Text(text, fontSize = 15.sp, lineHeight = 23.sp, color = Ink.TextDim)
}

/** Placeholder lines that breathe while Gemma works. */
@Composable
private fun Cleaning(active: Boolean) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val a by t.animateFloat(0.35f, 0.75f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Text(if (active) "Cleaning on this phone…" else "Waiting to clean…", fontFamily = Grotesk,
        fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = Ink.Text)
    listOf(0.92f, 0.78f, 0.85f, 0.55f).forEach { w ->
        Box(Modifier.fillMaxWidth(w).height(14.dp).clip(RoundedCornerShape(7.dp)).background(Ink.Raised.copy(alpha = a)))
    }
}

/** Title and bullets. Tap a bullet to see the exact words it came from (every AI line is traceable). */
@Composable
private fun CleanView(clean: String, transcript: String) {
    val lines = remember(clean) { CleanupText.parseStored(clean) }
    val segments = remember(transcript) { CleanupText.segments(transcript) }
    var open by remember(clean) { mutableStateOf<Int?>(null) }
    lines.forEachIndexed { i, l ->
        if (l.title) Text(l.text, fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp, color = Ink.Text)
        else Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .clickable { open = if (open == i) null else i }
            .semantics { contentDescription = "${l.text}. Tap to show the exact words." }
            .padding(vertical = 2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (l.task) Box(Modifier.padding(top = 4.dp).size(16.dp).border(1.5.dp, Ink.TextDim, RoundedCornerShape(4.dp)))
                else Box(Modifier.padding(top = 9.dp).size(5.dp).clip(CircleShape).background(Ink.TextBody))
                Text(l.text, fontSize = 15.sp, lineHeight = 23.sp, color = Ink.TextBody, modifier = Modifier.weight(1f))
            }
            if (open == i) {
                val said = l.refs.mapNotNull { segments.getOrNull(it - 1) }.joinToString(" ")
                Text("“$said”", fontSize = 13.sp, lineHeight = 20.sp, color = Ink.Amber,
                    modifier = Modifier.padding(start = 15.dp, top = 4.dp))
            }
        }
    }
}

@Composable
private fun Footer(c: Capture?, view: View, hasClean: Boolean, recordingThis: Boolean, private: Boolean, undone: Boolean) {
    val ctx = LocalContext.current
    if (c == null || recordingThis || undone) return
    val filed = c.state == CaptureStates.SAVED
    Text(when {
        view == View.Clean && hasClean -> "Your words, reordered. Nothing added. Tap a line to see what you said."
        view == View.Raw -> "Raw transcript is always kept with the audio."
        else -> ""
    }, fontSize = 12.sp, color = Ink.TextDim)
    if (filed) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !c.isPrivate && c.notePath != null) {
            c.notePath?.let { openInObsidian(ctx, it) }
        }.heightIn(min = 32.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Ink.Blue))
        Text(if (c.isPrivate || private) "Kept on this phone only" else "Filed to ${c.notePath?.substringBeforeLast('/') ?: "your vault"} · open",
            fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Ink.Blue)
    }
}

/** Real input level, newest on the right: amber while recording, grey otherwise. */
@Composable
private fun Waveform(level: Float, active: Boolean) {
    val bars = remember { mutableStateListOf<Float>().apply { repeat(28) { add(0f) } } }
    var current by remember { mutableStateOf(0f) }
    current = level
    LaunchedEffect(active) {
        while (true) { bars.removeAt(0); bars.add(if (active) current else 0f); delay(70) }
    }
    Row(Modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = "Input level" },
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        bars.forEach { v ->
            val h by animateFloatAsState(8f + 44f * v, spring(stiffness = 600f), label = "bar")
            Box(Modifier.width(5.dp).height(h.dp).clip(RoundedCornerShape(3.dp))
                .background(if (active) Ink.Amber.copy(alpha = 0.55f + 0.45f * v) else Ink.Bar))
        }
    }
}

@Composable
private fun Controls(
    recordingThis: Boolean, paused: Boolean, elapsedMs: () -> Long, canUndo: Boolean,
    onRecord: () -> Unit, onPause: () -> Unit, onDiscard: () -> Unit, onUndo: () -> Unit,
) {
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recordingThis) { while (recordingThis) { now = System.currentTimeMillis(); delay(250) } }
    val s = (elapsedMs() / 1000).also { now.let { } }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.width(76.dp)) {
            Text("%d:%02d".format(s / 60, s % 60), fontFamily = Mono, fontSize = 14.sp, color = Ink.TextSoft,
                modifier = Modifier.semantics { contentDescription = "${s / 60} minutes ${s % 60} seconds" })
            if (recordingThis) Text("Discard", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ink.Error,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onDiscard).padding(vertical = 10.dp))
        }
        BigButton(recordingThis, paused, onRecord)
        Box(Modifier.width(76.dp), contentAlignment = Alignment.CenterEnd) {
            when {
                recordingThis -> SmallAction(if (paused) "Resume" else "Pause", onPause)
                canUndo -> SmallAction("Undo", onUndo)
            }
        }
    }
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).heightIn(min = 44.dp).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.TextSoft)
    }
}

/** Amber circle: microphone to start, a square to stop and save. The halo breathes while recording. */
@Composable
private fun BigButton(recording: Boolean, paused: Boolean, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "halo")
    val halo by t.animateFloat(0.14f, 0.24f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "h")
    val press by animateFloatAsState(if (recording) 1f else 0.96f, spring(dampingRatio = 0.6f), label = "s")
    Box(Modifier.size(100.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(100.dp).clip(CircleShape).background(Ink.Amber.copy(alpha = if (recording && !paused) halo else 0.12f)))
        Surface(onClick = onClick, shape = CircleShape, color = Ink.Amber,
            modifier = Modifier.size(84.dp).scale(press).semantics {
                contentDescription = if (recording) "Stop and save" else "Start talking"
            }) {
            Box(contentAlignment = Alignment.Center) {
                if (recording) Box(Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(Ink.OnAmber))
                else Icon(painterResource(R.drawable.ic_mic), null, Modifier.size(34.dp), tint = Ink.OnAmber)
            }
        }
    }
}

@Composable
private fun ModeRow(mode: String, enabled: Boolean, onMode: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(CaptureActivity.MODE_TYPE to "Type", CaptureActivity.MODE_VOICE to "Voice", CaptureActivity.MODE_PASTE to "Paste").forEach { (m, label) ->
            val on = m == mode
            Surface(onClick = { if (enabled || m == CaptureActivity.MODE_VOICE) onMode(m) },
                shape = RoundedCornerShape(12.dp), color = if (on) Ink.Raised else Ink.Panel,
                modifier = Modifier.weight(1f).height(52.dp).semantics { selected = on; role = Role.Tab }) {
                Box(contentAlignment = Alignment.Center) {
                    Text(label, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) Ink.TextStrong else if (enabled || m == CaptureActivity.MODE_VOICE) Ink.TextSoft else Ink.Bar)
                }
            }
        }
    }
}

/** Type or paste: write it, save it. Saved at once; the voice tab shows it with Undo. */
@Composable
private fun TextCard(mode: String, private: Boolean, kind: String, onSaved: (Capture) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable(mode) { mutableStateOf("") }
    var saved by remember(mode) { mutableStateOf<Capture?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(mode) {
        if (mode == CaptureActivity.MODE_PASTE) {
            val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip
            text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
        } else runCatching { focus.requestFocus() }
    }
    Column(Modifier.fillMaxSize().padding(18.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val s = saved
        if (s != null) {
            Hint("Saved", if (s.isPrivate) "Kept on this phone only." else "Filed to ${s.notePath?.substringBeforeLast('/') ?: "your vault"}.")
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Undo", primary = false) { scope.launch { runCatching { Notes.delete(ctx, s) }; saved = null } }
                PillButton("New", primary = true) { saved = null; text = "" }
            }
            return@Column
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (text.isEmpty()) Text(if (mode == CaptureActivity.MODE_PASTE) "The clipboard is empty." else "Type anything…",
                fontSize = 15.sp, color = Ink.TextDim)
            BasicTextField(text, { text = it }, Modifier.fillMaxSize().focusRequester(focus),
                textStyle = TextStyle(fontFamily = Inter, fontSize = 15.sp, lineHeight = 24.sp, color = Ink.TextBody),
                cursorBrush = SolidColor(Ink.Amber))
        }
        error?.let { Text(it, fontSize = 12.sp, color = Ink.Error) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (private) "Kept on this phone only" else if (kind == CaptureTypes.TASK) "Saved as a task note" else "Saved to your inbox",
                fontSize = 12.sp, color = Ink.TextDim, modifier = Modifier.weight(1f))
            PillButton(if (busy) "Saving…" else "Save", primary = true, enabled = text.isNotBlank() && !busy) {
                busy = true; error = null
                scope.launch {
                    runCatching { TextCapture.save(ctx, text.trim(), if (private) CaptureTypes.PRIVATE else kind) }
                        .onSuccess { saved = it; onSaved(it) }
                        .onFailure { error = it.message ?: "Could not save" }
                    busy = false
                }
            }
        }
    }
}

@Composable
private fun PillButton(label: String, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(999.dp),
        color = if (primary) Ink.Amber.copy(alpha = if (enabled) 1f else 0.4f) else Color.Transparent,
        border = if (primary) null else BorderStroke(1.dp, Ink.Line),
        modifier = Modifier.heightIn(min = 44.dp).widthIn(min = 88.dp)) {
        Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (primary) Ink.OnAmber else Ink.TextSoft)
        }
    }
}
