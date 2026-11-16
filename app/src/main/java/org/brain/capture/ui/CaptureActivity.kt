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
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The capture screen on its own, for the widget and the quick settings tile. Inside the app the
 * same screen grows out of the main screen instead (see MainActivity), so there is no jump.
 */
class CaptureActivity : ComponentActivity() {
    private val request = mutableStateOf<CaptureRequest?>(null)

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

    private fun Intent.toRequest() = CaptureRequest(
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

/**
 * What to open: a mode, a type, a note to show (and add to), and whether to start talking at once.
 * [at] makes each request unique, so the same request twice still acts twice.
 */
data class CaptureRequest(
    val mode: String = CaptureActivity.MODE_VOICE,
    val type: String = CaptureTypes.IDEA,
    val start: Boolean = false,
    val noteId: String? = null,
    val at: Long = System.nanoTime(),
)

private enum class View { Clean, Raw }

/**
 * Screen 5 of the UX concepts. One note at a time: the note plus everything added to it.
 * The big button starts a new note, or adds to the note on screen ("New note" starts fresh).
 * [buttonModifier] lets the main screen slide its big button down into this one.
 */
@Composable
fun CaptureScreen(request: CaptureRequest?, onClose: () -> Unit, buttonModifier: Modifier = Modifier, cardModifier: Modifier = Modifier) {
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
    var noteId by rememberSaveable { mutableStateOf(request?.noteId ?: recording?.let { it.appendTo ?: it.captureId }) }
    var view by rememberSaveable { mutableStateOf(View.Clean) }
    var userPickedView by rememberSaveable { mutableStateOf(false) }
    var undone by remember { mutableStateOf(false) }

    // Whatever is being recorded belongs to the note on screen (a new one, or an addition).
    LaunchedEffect(recording?.captureId) {
        val r = recording ?: return@LaunchedEffect
        noteId = r.appendTo ?: r.captureId
        undone = false; userPickedView = false; view = View.Raw
    }
    val note by remember(noteId) { noteId?.let { dao.watch(it) } ?: flowOf(null) }.collectAsState(null)
    val additions by remember(noteId) { noteId?.let { dao.watchAdditions(it) } ?: flowOf(emptyList()) }.collectAsState(emptyList())
    val sections = remember(note, additions) { listOfNotNull(note) + additions }
    val recordingHere = recording != null && (recording?.captureId == noteId || recording?.appendTo == noteId)
    // Adding to the note on screen: only once it is saved, and not to private notes (they never touch the vault).
    val canAdd = note?.state == CaptureStates.SAVED && note?.isPrivate == false && !undone

    fun startVoice(appendTo: Capture?) {
        if (CaptureState.isRecording) return
        CaptureService.send(ctx, CaptureService.ACTION_START) {
            putExtra(CaptureService.EXTRA_TYPE, if (appendTo != null) appendTo.type else type)
            if (appendTo != null) {
                putExtra(CaptureService.EXTRA_APPEND_TO, appendTo.id)
                putExtra(CaptureService.EXTRA_APPEND_LABEL, appendTo.notePath)
            }
        }
    }
    var pendingAppend by remember { mutableStateOf<Capture?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) startVoice(pendingAppend)
    }
    fun talk(appendTo: Capture?) {
        mode = CaptureActivity.MODE_VOICE
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoice(appendTo)
        else { pendingAppend = appendTo; permission.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)) }
    }
    fun newNote() { noteId = null; undone = false; userPickedView = false; view = View.Raw }

    // A widget, tile or app request: switch to it, and start talking if asked.
    LaunchedEffect(request?.at) {
        val r = request ?: return@LaunchedEffect
        mode = r.mode
        if (r.type == CaptureTypes.PRIVATE) private = true else { kind = r.type; private = false }
        if (r.noteId != null) noteId = r.noteId else if (r.start && recording == null) newNote()
        if (r.start && r.mode == CaptureActivity.MODE_VOICE && recording == null)
            talk(r.noteId?.let { dao.get(it) }?.takeIf { it.state == CaptureStates.SAVED && !it.isPrivate })
    }
    // Switching Open/Private or Idea/Task while recording a new note changes where it goes.
    LaunchedEffect(type, recording?.captureId) {
        val r = recording ?: return@LaunchedEffect
        if (r.appendTo != null) return@LaunchedEffect
        val c = r.captureId?.let { dao.get(it) } ?: return@LaunchedEffect
        if (c.state != CaptureStates.SAVED && c.type != type) dao.setType(c.id, type)
    }
    // Show the clean version once there is one; the exact words when the check kept them.
    val last = sections.lastOrNull()
    val lastFailed = last?.cleanup != null && last.clean == null && last.kind == "voice"
    // Start on the exact words as they arrive; glide over to Clean once the AI is done.
    LaunchedEffect(last?.clean, sections.size) { if (!userPickedView) view = if (last?.clean != null) View.Clean else View.Raw }
    // The note shown decides the zone and kind chips (an opened private note shows Private).
    LaunchedEffect(note?.id) { note?.let { if (it.isPrivate) private = true else { private = false; kind = it.type } } }

    Box(Modifier.fillMaxSize().background(Ink.Ground)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TopBar(private, locked = recording?.appendTo != null,
                onPrivate = { p -> if ((note?.state == CaptureStates.SAVED || undone) && p != private) newNote(); private = p }, onClose = onClose)

            // A typed or pasted note has no audio and no clean version: no voice tabs for it.
            val typedNote = note != null && note?.kind != "voice" && additions.none { it.kind == "voice" } && !recordingHere && !undone
            if (mode == CaptureActivity.MODE_VOICE && typedNote) {
                Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (note?.kind == "link") "Saved link" else "Typed note", fontFamily = Grotesk,
                        fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = Ink.Text)
                }
            } else if (mode == CaptureActivity.MODE_VOICE) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Left to right in the order they happen: the exact words first, then the clean version.
                    ViewTab("Exactly what I said", "instant via Whisper", view == View.Raw) { view = View.Raw; userPickedView = true }
                    ViewTab("Clean", if (private) "on this phone" else "via AI", view == View.Clean) { view = View.Clean; userPickedView = true }
                    Spacer(Modifier.weight(1f))
                    if (!private && (note == null || recordingHere && recording?.appendTo == null))
                        KindChip(kind) { kind = if (kind == CaptureTypes.TASK) CaptureTypes.IDEA else CaptureTypes.TASK }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (mode == CaptureActivity.MODE_TYPE) "Type a thought" else "Paste from clipboard",
                        fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = Ink.Text,
                        modifier = Modifier.weight(1f))
                    if (!private) KindChip(kind) { kind = if (kind == CaptureTypes.TASK) CaptureTypes.IDEA else CaptureTypes.TASK }
                }
            }

            Surface(cardModifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(18.dp), color = Ink.Panel) {
                when (mode) {
                    CaptureActivity.MODE_VOICE -> NoteCardView(
                        sections = if (undone) emptyList() else sections, view = view, recording = recording,
                        liveText = live, cleaningId = cleaningId, gemmaInstalled = remember { GemmaHolder.modelFile(ctx) != null },
                        undone = undone,
                    )
                    else -> TextCard(mode, private, kind, onSaved = { c ->
                        noteId = c.id; undone = false; mode = CaptureActivity.MODE_VOICE; userPickedView = false
                    })
                }
            }

            if (mode == CaptureActivity.MODE_VOICE) {
                Waveform(level, active = recordingHere && recording?.paused != true)
                // What the big button will do, so it is never a surprise.
                val adding = canAdd && !recordingHere
                Row(Modifier.fillMaxWidth().height(20.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    when {
                        recordingHere && recording?.appendTo != null -> Text("Adding to this note", fontSize = 12.sp, color = Ink.TextDim)
                        recording != null && !recordingHere -> Text("Recording another note", fontSize = 12.sp, color = Ink.TextDim)
                        adding -> {
                            Text("Talk to add to this note · ", fontSize = 12.sp, color = Ink.TextDim)
                            Text("New note", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ink.Amber,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { newNote() }.padding(horizontal = 4.dp, vertical = 2.dp))
                        }
                    }
                }
                Controls(
                    recordingHere = recordingHere, paused = recording?.paused == true,
                    elapsedMs = { if (recordingHere) recording?.elapsedMs() ?: 0 else ((last?.audioSeconds ?: 0.0) * 1000).toLong() },
                    canUndo = !recordingHere && additions.isEmpty() && note?.state == CaptureStates.SAVED && !undone,
                    level = level, buttonModifier = buttonModifier,
                    onRecord = {
                        when {
                            recording != null -> CaptureService.send(ctx, CaptureService.ACTION_STOP)
                            adding -> talk(note)
                            else -> { newNote(); talk(null) }
                        }
                    },
                    onPause = { CaptureService.send(ctx, if (recording?.paused == true) CaptureService.ACTION_RESUME else CaptureService.ACTION_PAUSE) },
                    onDiscard = {
                        val wasAddition = recording?.appendTo != null
                        CaptureService.send(ctx, CaptureService.ACTION_CANCEL)
                        if (!wasAddition) newNote()
                    },
                    onUndo = {
                        val c = note ?: return@Controls
                        undone = true
                        scope.launch { runCatching { Notes.delete(ctx, c) } }
                    },
                )
            }

            ModeRow(mode, enabled = recording == null) { m -> if (m != mode) { mode = m; if (m != CaptureActivity.MODE_VOICE) newNote() } }
        }
    }
}

@Composable
private fun TopBar(private: Boolean, locked: Boolean, onPrivate: (Boolean) -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        // Just the chevron (no tile behind it); the touch area stays 44 dp.
        Surface(onClick = onClose, shape = CircleShape, color = Color.Transparent,
            modifier = Modifier.size(44.dp).semantics { contentDescription = "Back" }) {
            Box(contentAlignment = Alignment.CenterStart) {
                Icon(painterResource(R.drawable.ic_back), null, Modifier.size(24.dp), tint = Ink.TextSoft)
            }
        }
        Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Ink.Well).padding(3.dp)
            .semantics { contentDescription = "Zone" }) {
            Segment("Open", !private, !locked) { onPrivate(false) }
            Segment("Private", private, !locked) { onPrivate(true) }
        }
    }
}

@Composable
private fun Segment(label: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) Ink.Raised else Color.Transparent, label = "seg")
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(bg).clickable(enabled = enabled, onClick = onClick)
        .semantics { selected = on; role = Role.Tab }.padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = if (on) Ink.TextStrong else if (enabled) Ink.TextDim else Ink.Bar)
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

/** Idea or Task: the instruction Gemma follows. A tap switches; nothing to decide up front. */
@Composable
private fun KindChip(kind: String, onToggle: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onToggle)
        .semantics { contentDescription = "Capture type ${if (kind == CaptureTypes.TASK) "Task" else "Idea"}, tap to switch" }
        .heightIn(min = 44.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(if (kind == CaptureTypes.TASK) "Task ⇄" else "Idea ⇄", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ink.TextDim)
    }
}

/** One heartbeat, about 55 a minute. */
private const val HEARTBEAT_MS = 1100

private val TIME = SimpleDateFormat("HH:mm", Locale.ROOT)

/** The whole note: its first capture, then each addition under a small "Added 19:22" line. */
@Composable
private fun NoteCardView(
    sections: List<Capture>, view: View, recording: org.brain.capture.RecordingInfo?, liveText: org.brain.capture.LiveText?,
    cleaningId: String?, gemmaInstalled: Boolean, undone: Boolean,
) {
    val scroll = rememberScrollState()
    // Keep the newest words in sight as they arrive.
    LaunchedEffect(sections.size, sections.lastOrNull()?.clean, sections.lastOrNull()?.transcript, liveText?.text, recording?.captureId) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    val recordingSection = recording?.captureId
    AnimatedContent(view, label = "view", transitionSpec = {
        (fadeIn(tween(520, 120, FastOutSlowInEasing)) + slideInHorizontally(tween(560, 80, FastOutSlowInEasing)) { if (targetState == View.Clean) it / 8 else -it / 8 }) togetherWith
            (fadeOut(tween(260)) + slideOutHorizontally(tween(420, easing = FastOutSlowInEasing)) { if (targetState == View.Clean) -it / 10 else it / 10 })
    }) { view ->
    Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            undone -> Hint("Undone", "The note moved to the vault's .trash folder (private ones are deleted). Tap the button to talk again.")
            sections.isEmpty() && recording == null -> Hint("Tap to talk", "Say it however it comes. Brain files it for you; nothing to decide now.")
            sections.isEmpty() -> Hint("Starting…", "")
        }
        sections.forEachIndexed { i, c ->
            if (i > 0) Text("Added ${TIME.format(Date(c.createdAt))}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = Ink.TextDim, modifier = Modifier.padding(top = 8.dp))
            Section(c, view, recordingThis = c.id == recordingSection, paused = recording?.paused == true,
                live = liveText?.takeIf { it.captureId == c.id }?.text, cleaning = cleaningId == c.id,
                gemmaInstalled = gemmaInstalled, first = i == 0)
        }
        // The footer only once everything in the note is done (no half-finished promise).
        val busy = recording != null && sections.any { it.id == recordingSection } ||
            sections.any { it.kind == "voice" && (it.transcript == null || (it.clean == null && it.cleanup == null && gemmaInstalled && !it.transcript.isNullOrBlank())) }
        if (!undone) Footer(sections.firstOrNull(), view, busy)
    }
    }
}

@Composable
private fun Section(c: Capture, view: View, recordingThis: Boolean, paused: Boolean, live: String?, cleaning: Boolean, gemmaInstalled: Boolean, first: Boolean) {
    val transcript = c.transcript
    when {
        recordingThis -> Hint(if (paused) "Paused" else "Listening…",
            if (view == View.Clean) "Talk freely. The clean version appears when you stop." else "Your exact words appear the moment you stop.")
        c.kind != "voice" -> Text(transcript.orEmpty(), fontSize = 15.sp, lineHeight = 24.sp, color = Ink.TextBody)
        view == View.Raw -> {
            val words = transcript ?: live
            if (words.isNullOrBlank()) Hint("Transcribing…", "Whisper is writing down your words, on this phone.")
            else Text(words, fontSize = 15.sp, lineHeight = 24.sp, color = Ink.TextBody)
        }
        c.clean != null -> CleanView(c.clean, transcript.orEmpty(), showTitle = first)
        transcript == null -> Hint("Transcribing…", "The clean version follows as soon as Whisper is done.")
        transcript.isBlank() -> Hint("No speech recognised", "The recording is kept with the note.")
        c.cleanup != null || !gemmaInstalled -> {
            Text(transcript, fontSize = 15.sp, lineHeight = 24.sp, color = Ink.TextBody)
            Text(if (gemmaInstalled) "Kept your exact words: the clean version didn't pass the check." else "No cleanup model on this phone yet.",
                fontSize = 12.sp, color = Ink.TextDim)
        }
        else -> Cleaning(cleaning)
    }
}

@Composable
private fun Hint(title: String, text: String) {
    Text(title, fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp, color = Ink.Text)
    if (text.isNotEmpty()) Text(text, fontSize = 15.sp, lineHeight = 23.sp, color = Ink.TextDim)
}

/** Placeholder lines that breathe while Gemma works. */
@Composable
private fun Cleaning(active: Boolean) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val a by t.animateFloat(0.35f, 0.75f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Text(if (active) "Cleaning on this phone…" else "Cleaning next, on this phone…", fontFamily = Grotesk,
        fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Ink.Text)
    listOf(0.92f, 0.78f, 0.6f).forEach { w ->
        Box(Modifier.fillMaxWidth(w).height(14.dp).clip(RoundedCornerShape(7.dp)).background(Ink.Raised.copy(alpha = a)))
    }
}

/** Title and bullets. Tap a bullet to see the exact words it came from (every AI line is traceable). */
@Composable
private fun CleanView(clean: String, transcript: String, showTitle: Boolean) {
    val lines = remember(clean) { CleanupText.parseStored(clean) }
    val segments = remember(transcript) { CleanupText.segments(transcript) }
    var open by remember(clean) { mutableStateOf<Int?>(null) }
    lines.forEachIndexed { i, l ->
        if (l.title) { if (showTitle) Text(l.text, fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp, color = Ink.Text) }
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
private fun Footer(c: Capture?, view: View, recording: Boolean) {
    val ctx = LocalContext.current
    if (c == null || recording || c.state != CaptureStates.SAVED) return
    Spacer(Modifier.height(4.dp))
    Text(when {
        c.kind != "voice" -> "Your words, as you typed them."
        view == View.Clean -> "Your words, reordered. Nothing added. Tap a line to see what you said."
        else -> "Raw transcript is always kept with the audio."
    }, fontSize = 12.sp, color = Ink.TextDim)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !c.isPrivate && c.notePath != null) {
            c.notePath?.let { openInObsidian(ctx, it) }
        }.heightIn(min = 32.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Ink.Blue))
        Text(if (c.isPrivate) "Kept on this phone only" else "Filed to ${c.notePath?.substringBeforeLast('/') ?: "your vault"} · open in Obsidian",
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
    Row(Modifier.fillMaxWidth().height(52.dp).semantics { contentDescription = "Input level" },
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        bars.forEach { v ->
            val h by animateFloatAsState(8f + 42f * v, spring(stiffness = 600f), label = "bar")
            Box(Modifier.width(5.dp).height(h.dp).clip(RoundedCornerShape(3.dp))
                .background(if (active) Ink.Amber.copy(alpha = 0.55f + 0.45f * v) else Ink.Bar))
        }
    }
}

@Composable
private fun Controls(
    recordingHere: Boolean, paused: Boolean, elapsedMs: () -> Long, canUndo: Boolean, level: Float, buttonModifier: Modifier,
    onRecord: () -> Unit, onPause: () -> Unit, onDiscard: () -> Unit, onUndo: () -> Unit,
) {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recordingHere) { while (recordingHere) { tick = System.currentTimeMillis(); delay(250) } }
    @Suppress("UNUSED_EXPRESSION") tick
    val ms = elapsedMs()
    val s = ms / 1000
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.width(76.dp)) {
            // Only a time that means something: the running recording, or the length of the last one.
            if (recordingHere || ms > 0) Text("%d:%02d".format(s / 60, s % 60), fontFamily = Mono, fontSize = 14.sp, color = Ink.TextSoft,
                modifier = Modifier.semantics { contentDescription = "${s / 60} minutes ${s % 60} seconds" })
            if (recordingHere) Text("Discard", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ink.Error,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onDiscard).padding(vertical = 10.dp))
        }
        BigButton(recordingHere, paused, level, 84.dp, buttonModifier, onRecord)
        Box(Modifier.width(76.dp), contentAlignment = Alignment.CenterEnd) {
            when {
                recordingHere -> SmallAction(if (paused) "Resume" else "Pause", onPause)
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

/**
 * The amber button, used on the main screen and the capture screen (the main screen's one slides
 * down into this place). Microphone to start, a square to stop and save. While recording, a soft
 * halo breathes around it in a slow, even rhythm (no beat, no thump); the button stays still.
 */
@Composable
fun BigButton(recording: Boolean, paused: Boolean, level: Float, size: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "heart")
    // A resting heartbeat (about 55 a minute): a strong "lub", a softer "dub" right after,
    // then a long, slow ease back to rest before the next beat.
    val beat by t.animateFloat(0f, 0f, infiniteRepeatable(keyframes {
        durationMillis = HEARTBEAT_MS
        0f at 0 using FastOutLinearInEasing
        1f at 110 using LinearOutSlowInEasing
        0.28f at 250 using FastOutLinearInEasing
        0.62f at 360 using LinearOutSlowInEasing
        0f at HEARTBEAT_MS
    }), label = "beat")
    // The same clock, linear: drives the soft ring that travels outward from each beat.
    val echo by t.animateFloat(0f, 1f, infiniteRepeatable(tween(HEARTBEAT_MS, easing = LinearEasing)), label = "echo")
    val pulsing = recording && !paused
    val on by animateFloatAsState(if (pulsing) 1f else 0f, tween(700, easing = FastOutSlowInEasing), label = "on")
    Box(modifier.size(size * 1.6f), contentAlignment = Alignment.Center) {
        // The beat's ripple: leaves the button with the "lub" and fades as it spreads.
        Box(Modifier.size(size).graphicsLayer {
            val e = LinearOutSlowInEasing.transform(echo)
            val s = 1f + 0.55f * e
            scaleX = s; scaleY = s; alpha = on * 0.26f * (1f - echo) * (1f - echo)
        }.clip(CircleShape).background(Ink.Amber))
        // Soft glow that swells with each beat; at rest it settles to a faint ring.
        Box(Modifier.size(size).graphicsLayer {
            val s = 1.19f + on * (0.04f + 0.16f * beat)
            scaleX = s; scaleY = s; alpha = 0.12f + on * 0.14f * beat
        }.clip(CircleShape).background(Ink.Amber))
        Surface(onClick = onClick, shape = CircleShape, color = Ink.Amber, shadowElevation = 4.dp,
            modifier = Modifier.size(size).graphicsLayer {
                // The button itself gives a small, physical thump on the beat.
                val s = 1f + on * 0.035f * beat
                scaleX = s; scaleY = s
            }.semantics {
                contentDescription = if (recording) "Stop and save" else "Start talking"
            }) {
            Box(contentAlignment = Alignment.Center) {
                if (recording) Box(Modifier.size(size * 0.31f).clip(RoundedCornerShape(6.dp)).background(Ink.OnAmber))
                else Icon(painterResource(R.drawable.ic_mic), null, Modifier.size(size * 0.4f), tint = Ink.OnAmber)
            }
        }
    }
}

@Composable
private fun ModeRow(mode: String, enabled: Boolean, onMode: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(CaptureActivity.MODE_TYPE to "Type", CaptureActivity.MODE_VOICE to "Voice", CaptureActivity.MODE_PASTE to "Paste").forEach { (m, label) ->
            val on = m == mode
            val usable = enabled || m == CaptureActivity.MODE_VOICE
            Surface(onClick = { if (usable) onMode(m) },
                shape = RoundedCornerShape(12.dp), color = if (on) Ink.Raised else Ink.Panel,
                modifier = Modifier.weight(1f).height(52.dp).semantics { selected = on; role = Role.Tab }) {
                Box(contentAlignment = Alignment.Center) {
                    Text(label, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) Ink.TextStrong else if (usable) Ink.TextSoft else Ink.Bar)
                }
            }
        }
    }
}

/** Type or paste: write it, save it. After saving, the note shows like any other, with Undo. */
@Composable
private fun TextCard(mode: String, private: Boolean, kind: String, onSaved: (Capture) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable(mode) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(mode) {
        if (mode == CaptureActivity.MODE_PASTE) {
            val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip
            text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
        } else runCatching { focus.requestFocus() }
    }
    // No extra keyboard padding here: the screen already makes room for the keyboard.
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (text.isEmpty()) Text(if (mode == CaptureActivity.MODE_PASTE) "The clipboard is empty." else "Type anything…",
                fontSize = 15.sp, color = Ink.TextDim)
            BasicTextField(text, { text = it }, Modifier.fillMaxSize().focusRequester(focus),
                textStyle = TextStyle(fontFamily = Inter, fontSize = 15.sp, lineHeight = 24.sp, color = Ink.TextBody),
                cursorBrush = SolidColor(Ink.Amber))
        }
        error?.let { Text(it, fontSize = 12.sp, color = Ink.Error) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (private) "Kept on this phone only" else if (kind == CaptureTypes.TASK) "Goes to your inbox as a task note" else "Goes to your inbox",
                fontSize = 12.sp, color = Ink.TextDim, modifier = Modifier.weight(1f))
            PillButton(if (busy) "Saving…" else "Save", enabled = text.isNotBlank() && !busy) {
                busy = true; error = null
                scope.launch {
                    runCatching { TextCapture.save(ctx, text.trim(), if (private) CaptureTypes.PRIVATE else kind) }
                        .onSuccess { text = ""; onSaved(it) }
                        .onFailure { error = it.message ?: "Could not save" }
                    busy = false
                }
            }
        }
    }
}

@Composable
private fun PillButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(999.dp),
        color = Ink.Amber.copy(alpha = if (enabled) 1f else 0.4f),
        modifier = Modifier.heightIn(min = 44.dp).widthIn(min = 88.dp)) {
        Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink.OnAmber)
        }
    }
}
