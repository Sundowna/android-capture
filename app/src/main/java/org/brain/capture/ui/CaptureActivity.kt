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
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.text.withStyle
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
import org.brain.capture.types.Profiles
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
        // The chevron is "up", not "back": it goes to the app's notes list, not to whatever was
        // open before the widget or tile (usually the phone's home screen). System back still leaves.
        setContent { CaptureTheme { CaptureScreen(request.value, onClose = { goHome() }) } }
    }

    private fun goHome() {
        startActivity(Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_HOME, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
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
        /** Editing the note on screen (only from within the note screen, never from outside). */
        const val MODE_EDIT = "edit"

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
@android.annotation.SuppressLint("ModifierParameter") // two named modifiers: the shared big button and the card
@Composable
fun CaptureScreen(request: CaptureRequest?, onClose: () -> Unit, buttonModifier: Modifier = Modifier, cardModifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { CaptureDb.get(ctx).dao() }
    val recording by CaptureState.recording.collectAsState()
    val live by CaptureState.live.collectAsState()
    // The voice level changes 20 times a second: read it only where it is drawn, never here.
    val levelState = CaptureState.level.collectAsState()
    val level = remember { { levelState.value } }
    val cleaningId by CleanupStep.cleaning.collectAsState()
    val recleanUndo by CleanupStep.lastReclean.collectAsState()

    var mode by rememberSaveable { mutableStateOf(request?.mode ?: CaptureActivity.MODE_VOICE) }
    // The capture type ("agent"): one for the Open zone, one for the Private zone (Settings → Capture types).
    val profiles by remember { Profiles.flow(ctx) }.collectAsState()
    var kind by rememberSaveable { mutableStateOf(request?.type?.takeIf { !Profiles.isPrivateId(it) } ?: Profiles.primary(ctx).id) }
    var privateKind by rememberSaveable { mutableStateOf(request?.type?.takeIf { Profiles.isPrivateId(it) }
        ?: Profiles.all(ctx).firstOrNull { it.private }?.id ?: Profiles.PRIVATE) }
    var private by rememberSaveable { mutableStateOf(Profiles.isPrivateId(request?.type)) }
    val type = if (private) privateKind else kind
    val zoneTypes = profiles.orEmpty().filter { it.private == private }
    var noteId by rememberSaveable { mutableStateOf(request?.noteId ?: recording?.let { it.appendTo ?: it.captureId }) }
    var view by rememberSaveable { mutableStateOf(View.Clean) }
    var userPickedView by rememberSaveable { mutableStateOf(false) }
    var undone by remember { mutableStateOf(false) }
    // The note left by switching Open/Private, and its zone: switching back returns to it.
    var leftNote by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

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
    val gemmaInstalled = remember { GemmaHolder.modelFile(ctx) != null }
    // Still being transcribed or cleaned: the note is not finished yet.
    // Typed text saved "with AI" counts too while it waits for its clean version.
    val noteBusy = sections.any { it.kind == "voice" &&
        (it.transcript == null || (it.clean == null && it.cleanup == null && gemmaInstalled && it.transcript.isNotBlank())) ||
        it.kind == "text" && it.clean == null && it.cleanup == TextCapture.AI_PENDING }
    // Adding to the note on screen: only once it is saved, and not to private notes (they never touch the vault).
    val canAdd = note?.state == CaptureStates.SAVED && note?.isPrivate == false && !undone
    // Editing the note on screen: once it is finished, and not while recording, transcribing or cleaning.
    val canEdit = note?.state == CaptureStates.SAVED && !undone && recording == null && !noteBusy &&
        sections.any { org.brain.capture.pipeline.NoteEdits.wordsEditable(it) }
    // Which section the cursor starts in, and which version is edited (the view on screen when it began).
    var editFocus by remember { mutableStateOf<String?>(null) }
    var editClean by remember { mutableStateOf(false) }
    fun startEdit(sectionId: String?) {
        if (!canEdit) return
        editFocus = sectionId ?: sections.lastOrNull { org.brain.capture.pipeline.NoteEdits.wordsEditable(it) }?.id
        editClean = view == View.Clean && sections.any { it.clean != null }
        mode = CaptureActivity.MODE_EDIT
    }
    // Asked for from a card (long-press on its text): edit as soon as the note is there.
    var editWanted by remember { mutableStateOf(false) }
    LaunchedEffect(editWanted, canEdit) { if (editWanted && canEdit) { editWanted = false; startEdit(null) } }
    // Back leaves Type, Paste or editing first, back to the note; only then the screen itself.
    androidx.activity.compose.BackHandler(enabled = mode != CaptureActivity.MODE_VOICE) { mode = CaptureActivity.MODE_VOICE }

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
        else { pendingAppend = appendTo; permission.launch(if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            else arrayOf(Manifest.permission.RECORD_AUDIO)) }
    }
    fun newNote() { noteId = null; undone = false; userPickedView = false; view = View.Raw }
    LaunchedEffect(recording?.captureId) { if (recording != null) leftNote = null }

    // A widget, tile or app request: switch to it, and start talking if asked.
    LaunchedEffect(request?.at) {
        val r = request ?: return@LaunchedEffect
        // Edit (a long-press on a card) waits until the note is loaded and editable; see below.
        if (r.mode == CaptureActivity.MODE_EDIT) { mode = CaptureActivity.MODE_VOICE; editWanted = true } else mode = r.mode
        if (Profiles.isPrivateId(r.type)) { private = true; privateKind = r.type } else { kind = r.type; private = false }
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
    // Captures this screen saw arrive (recorded or transcribed while open): their words type out.
    val watched = remember { mutableStateListOf<String>() }
    val revealed = remember { mutableStateMapOf<String, Int>() }
    var typedDone by remember { mutableStateOf<String?>(null) }

    // Photo button: the phone's camera app; the photo becomes a new note or is added to this one.
    var photoSaving by remember { mutableStateOf(false) }
    var photoTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val takePhoto = rememberTakePhoto(
        onSaving = { photoSaving = true },
        onDone = { id, message ->
            photoSaving = false
            if (id != null) {
                noteId = photoTarget ?: id; undone = false; mode = CaptureActivity.MODE_VOICE; userPickedView = false
                if (id !in watched) watched += id // made here: Undo can take it back
            } else if (message != null) android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_LONG).show()
            photoTarget = null
        })
    fun photo() {
        photoTarget = note?.takeIf { canAdd }?.id
        takePhoto(photoTarget)
    }
    // Attach (paperclip): the same, from Gallery, Files, Contacts, a link or where you are.
    var attachOpen by rememberSaveable { mutableStateOf(false) }
    var attachTarget by rememberSaveable { mutableStateOf<String?>(null) }
    AttachSheet(attachOpen, attachTarget, onDismiss = { attachOpen = false }, onSaving = { photoSaving = true },
        onDone = { id, message ->
            photoSaving = false
            if (id != null) {
                noteId = attachTarget ?: id; undone = false; mode = CaptureActivity.MODE_VOICE; userPickedView = false
                if (id !in watched) watched += id
            } else if (message != null) android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_LONG).show()
        })
    fun attach() {
        attachTarget = note?.takeIf { canAdd }?.id
        attachOpen = true
    }
    LaunchedEffect(sections, recording?.captureId) {
        recording?.captureId?.let { if (it !in watched) watched += it }
        sections.forEach { if (it.kind == "voice" && it.transcript == null && it.id !in watched) watched += it.id }
    }
    // Exact words first, typed out as they arrive; a second after the last letter, glide over to
    // Clean, where "Cleaning on this phone" pulses until the clean version is there.
    LaunchedEffect(last?.id, last?.clean != null, typedDone, recordingHere) {
        if (userPickedView) return@LaunchedEffect
        val l = last
        // A type without AI cleanup stays on the exact words: there is no clean version coming.
        val noAi = Profiles.get(ctx, note?.type ?: l?.type).noCleanup
        when {
            l == null || recordingHere || noAi -> view = View.Raw
            l.id in watched -> if (typedDone == l.id && !l.transcript.isNullOrBlank()) { delay(1000); view = View.Clean } else view = View.Raw
            else -> view = if (l.clean != null) View.Clean else View.Raw
        }
    }
    // The note shown decides the zone and kind chips (an opened private note shows Private).
    LaunchedEffect(note?.id) { note?.let { if (it.isPrivate) { private = true; privateKind = it.type } else { private = false; kind = it.type } } }

    Box(Modifier.fillMaxSize().background(Ink.Ground)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TopBar(private, locked = recording?.appendTo != null,
                onPrivate = { p ->
                    if (p != private) {
                        val n = note
                        when {
                            // Leaving a finished note for the other zone: start fresh there, but remember it…
                            n != null && n.state == CaptureStates.SAVED && !undone -> { leftNote = n.id to n.isPrivate; newNote() }
                            // …so tapping back (before anything new was started) returns to it.
                            noteId == null && recording == null && leftNote?.second == p -> { noteId = leftNote?.first; leftNote = null }
                            undone -> newNote()
                        }
                        private = p
                    }
                }, onClose = onClose)

            // A typed or pasted note has no audio and no clean version: no voice tabs for it.
            // A photo note looks like a voice note: the same tabs and type drop-down, so words added to it are cleaned the same way.
            val photoNote = Photos.isPhoto(note)
            // (Typed text saved with AI gets the voice tabs too, so its clean version can be seen.)
            val aiText = sections.any { it.kind == "text" && (it.clean != null || it.cleanup == TextCapture.AI_PENDING) }
            val typedNote = note != null && note?.kind != "voice" && !photoNote && !aiText && additions.none { it.kind == "voice" } && !recordingHere && !undone
            // Every opened note shows its capture type at the top right, like on Home's widget and cards.
            // A finished note: pick a type (or the same one) and it is cleaned again with that instruction;
            // types without AI cleanup aren't offered for that (except the note's own, so the chip names it).
            @Composable
            fun NoteTypeChip(n: Capture) {
                val canPick = n.state == CaptureStates.SAVED && recording == null && !noteBusy && gemmaInstalled
                TypeChip(n.type, profiles.orEmpty().filter { it.private == n.isPrivate && (!it.noCleanup || it.id == n.type) },
                    again = true, enabled = canPick) { t ->
                    if (n.isPrivate) privateKind = t else kind = t
                    userPickedView = true; view = View.Clean
                    org.brain.capture.share.AppScope.launch { CleanupStep.reclean(ctx, n.id, t) }
                }
            }
            if (mode == CaptureActivity.MODE_VOICE && typedNote) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(when {
                        Photos.isPhoto(note) -> "Photo"
                        note?.kind == "link" -> "Saved link"; note?.kind == "file" -> "Shared file"; else -> "Typed note" }, fontFamily = Grotesk,
                        fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = Ink.Text, modifier = Modifier.weight(1f))
                    note?.let { NoteTypeChip(it) }
                }
            } else if (mode == CaptureActivity.MODE_VOICE) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Left to right in the order they happen: the exact words first, then the clean version.
                    ViewTab("Exactly what I said", "instant via Whisper", view == View.Raw) { view = View.Raw; userPickedView = true }
                    ViewTab("Clean", if (private) "on this phone" else "via AI", view == View.Clean) { view = View.Clean; userPickedView = true }
                    Spacer(Modifier.weight(1f))
                    val n = note
                    if (zoneTypes.size > 1 && (n == null || recordingHere && recording?.appendTo == null))
                        TypeChip(type, zoneTypes) { if (private) privateKind = it else kind = it }
                    else if (n != null && !undone) NoteTypeChip(n)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(when {
                        mode == CaptureActivity.MODE_EDIT -> if (editClean) "Edit the clean version" else "Correct your exact words"
                        note?.state == CaptureStates.SAVED && !undone -> if (mode == CaptureActivity.MODE_TYPE) "Type to add to this note" else "Paste into this note"
                        mode == CaptureActivity.MODE_TYPE -> "Type a thought"
                        else -> "Paste from clipboard"
                    },
                        fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = Ink.Text,
                        modifier = Modifier.weight(1f))
                    if (zoneTypes.size > 1 && (note?.state != CaptureStates.SAVED || undone)) TypeChip(type, zoneTypes) { if (private) privateKind = it else kind = it }
                }
            }

            Surface(cardModifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(18.dp), color = Ink.Panel) {
                when (mode) {
                    CaptureActivity.MODE_VOICE -> NoteCardView(
                        sections = if (undone) emptyList() else sections, view = view, recording = recording,
                        liveText = live, cleaningId = cleaningId, gemmaInstalled = gemmaInstalled,
                        undone = undone, watched = watched, revealed = revealed, onTyped = { typedDone = it },
                        onEdit = if (canEdit) { id -> startEdit(id) } else null,
                    )
                    CaptureActivity.MODE_EDIT -> EditCard(sections, editClean, editFocus,
                        onCancel = { mode = CaptureActivity.MODE_VOICE },
                        onSaved = { wordsChanged ->
                            mode = CaptureActivity.MODE_VOICE
                            // Corrected words are cleaned again: show the clean version coming.
                            if (wordsChanged && gemmaInstalled) { view = View.Clean; userPickedView = true }
                        })
                    else -> TextCard(mode, private, type, appendTo = note?.takeIf { it.state == CaptureStates.SAVED && !undone }, onSaved = { c ->
                        noteId = c.appendTo ?: c.id; undone = false; mode = CaptureActivity.MODE_VOICE; userPickedView = false
                        if (c.id !in watched) watched += c.id // typed here: Undo can take it back
                    })
                }
            }

            if (mode == CaptureActivity.MODE_VOICE) {
                Waveform(level, active = recordingHere && recording?.paused != true)
                // What the big button will do, so it is never a surprise.
                val adding = canAdd && !recordingHere && !noteBusy
                Row(Modifier.fillMaxWidth().height(20.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    when {
                        recordingHere && recording?.appendTo != null -> Text("Adding to this note", fontSize = 12.sp, color = Ink.TextDim)
                        // Looking at another note while recording: say so, and one tap goes back to the one being recorded.
                        recording != null && !recordingHere -> {
                            Text(if (recording?.appendTo != null) "Adding to another note · " else "Recording another note · ",
                                fontSize = 12.sp, color = Ink.TextDim)
                            Text("Go to note", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ink.Amber,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                                    recording?.let { r -> noteId = r.appendTo ?: r.captureId; undone = false; userPickedView = false }
                                }.padding(horizontal = 4.dp, vertical = 2.dp))
                        }
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
                    // Undo only takes back what was just done here: a re-clean, or a recording or note made on this screen.
                    canUndo = recording == null && !noteBusy && !undone && note?.state == CaptureStates.SAVED &&
                        (recleanUndo?.noteId == noteId || (additions.lastOrNull()?.id ?: noteId) in watched),
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
                        val lastAddition = additions.lastOrNull()
                        when {
                            // 1. A re-clean: back to the previous type and clean version.
                            recleanUndo?.noteId == c.id -> scope.launch { CleanupStep.undoReclean(ctx) }
                            // 2. An addition: that part comes out of the note again.
                            lastAddition != null && lastAddition.id in watched -> scope.launch {
                                org.brain.capture.pipeline.Undo.forget(lastAddition.id)
                                runCatching { Notes.deleteAddition(ctx, lastAddition) }.onFailure {
                                    android.widget.Toast.makeText(ctx, it.message ?: "Could not undo", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                            // 3. A new note: it goes to the vault's .trash (private ones are deleted).
                            c.id in watched -> {
                                undone = true; org.brain.capture.pipeline.Undo.forget(c.id)
                                scope.launch { runCatching { Notes.delete(ctx, c, undoable = false) } }
                            }
                        }
                    },
                )
            }

            // Type and Paste add to the note on screen; with no finished note open they start a new one.
            // One bottom row on every note screen: Paste · Photo · Attach. Talking is the big mic, editing is
            // tapping the note's text. Photo and Attach add to the note on screen (never to private notes).
            val canAttach = recording == null && !private && !photoSaving
            if (mode != CaptureActivity.MODE_EDIT) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BarButton("Paste", R.drawable.ic_paste, selected = mode == CaptureActivity.MODE_PASTE, enabled = recording == null,
                    modifier = Modifier.weight(1f)) {
                    // Tapping Paste again goes back to the note.
                    if (mode == CaptureActivity.MODE_PASTE) mode = CaptureActivity.MODE_VOICE
                    else { mode = CaptureActivity.MODE_PASTE; if (note?.state != CaptureStates.SAVED) newNote() }
                }
                BarButton("Photo", R.drawable.t_camera, enabled = canAttach, modifier = Modifier.weight(1f)) { photo() }
                BarButton("Attach", R.drawable.ic_attach, enabled = canAttach, modifier = Modifier.weight(1f)) { attach() }
            }
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

/** The capture type for this note; a tap opens the list (Settings → Capture types edits them). */
@Composable
private fun TypeChip(selected: String, types: List<org.brain.capture.types.CaptureProfile>, again: Boolean = false,
                     enabled: Boolean = true, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = types.firstOrNull { it.id == selected } ?: types.first()
    // Always shows the note's type; while the note is still being worked on it can't be changed yet (dimmed).
    Box(Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.5f }) {
        Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled) { open = true }
            .semantics { contentDescription = "Capture type ${current.name}, tap to change" }
            .heightIn(min = 44.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(typeIcon(current.icon)), null, Modifier.size(16.dp), tint = Ink.Amber)
            Text("${current.name} ▾", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.Amber)
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }, containerColor = Ink.Panel) {
            if (again) Text("Clean again as…", fontSize = 12.sp, color = Ink.TextDim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            types.forEach { t ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Column {
                        Text(t.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (t.id == current.id) Ink.Amber else Ink.Text)
                        if (t.subtitle.isNotBlank()) Text(t.subtitle, fontSize = 12.sp, color = Ink.TextDim)
                    } },
                    leadingIcon = { Icon(painterResource(typeIcon(t.icon)), null, Modifier.size(18.dp), tint = Ink.TextSoft) },
                    onClick = { open = false; onPick(t.id) })
            }
        }
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
    watched: List<String>, revealed: MutableMap<String, Int>, onTyped: (String) -> Unit,
    onEdit: ((String) -> Unit)? = null,
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
            // Tapping a finished section's text edits it, in the view on screen.
            val edit = onEdit?.takeIf { org.brain.capture.pipeline.NoteEdits.wordsEditable(c) && c.transcript != null && cleaningId != c.id }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .then(if (edit != null) Modifier.clickable(onClickLabel = "Edit") { edit(c.id) } else Modifier)) {
                Section(c, view, recordingThis = c.id == recordingSection, paused = recording?.paused == true,
                    live = liveText?.takeIf { it.captureId == c.id }?.text, cleaning = cleaningId == c.id,
                    gemmaInstalled = gemmaInstalled, first = i == 0,
                    typing = c.id in watched, revealed = revealed, onTyped = onTyped, onEdit = edit?.let { e -> { e(c.id) } })
            }
        }
        // The footer only once everything in the note is done (no half-finished promise).
        val busy = recording != null && sections.any { it.id == recordingSection } ||
            sections.any { it.kind == "voice" && (it.transcript == null || (it.clean == null && it.cleanup == null && gemmaInstalled && !it.transcript.isNullOrBlank())) }
        if (!undone) Footer(sections.firstOrNull(), view, busy)
    }
    }
}

@Composable
private fun Section(
    c: Capture, view: View, recordingThis: Boolean, paused: Boolean, live: String?, cleaning: Boolean, gemmaInstalled: Boolean, first: Boolean,
    typing: Boolean, revealed: MutableMap<String, Int>, onTyped: (String) -> Unit, onEdit: (() -> Unit)? = null,
) {
    val transcript = c.transcript
    // Tap the words to edit them, like the clean version: typed text and exact words too.
    val tap = if (onEdit != null) Modifier.fillMaxWidth().clickable(onClickLabel = "Edit", onClick = onEdit) else Modifier
    when {
        recordingThis -> Hint(if (paused) "Paused" else "Listening…",
            if (view == View.Clean) "Talk freely. The clean version appears when you stop." else "Your exact words appear the moment you stop.")
        // Shared files: their thumbnails (tap one to open it), then any caption.
        c.kind == "file" -> SharedFiles(c)
        // Typed text without AI is shown as typed; with AI it is shown like a voice section (exact words / clean).
        c.kind == "text" && c.clean == null && c.cleanup == TextCapture.AI_PENDING ->
            if (view == View.Raw) Text(transcript.orEmpty(), tap, fontSize = 15.sp, lineHeight = 24.sp, color = Ink.TextBody) else Cleaning(cleaning)
        c.kind != "voice" && (c.kind != "text" || c.clean == null) ->
            Text(transcript.orEmpty(), tap, fontSize = 15.sp, lineHeight = 24.sp, color = Ink.TextBody)
        view == View.Raw -> {
            val words = transcript ?: live
            if (words.isNullOrBlank()) Hint("Transcribing…", "Whisper is writing down your words, on this phone.")
            else Box(if (transcript != null) tap else Modifier) { TypeText(words, c.id, animate = typing, final = transcript != null, revealed = revealed, onDone = onTyped) }
        }
        // Cleaning, then the clean version: one cross-fade from the pulsing placeholder to the bullets.
        c.clean != null || (!transcript.isNullOrBlank() && c.cleanup == null && gemmaInstalled) ->
            androidx.compose.animation.Crossfade(c.clean, animationSpec = tween(600, easing = FastOutSlowInEasing), label = "clean") { clean ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (clean != null) CleanView(clean, transcript.orEmpty(), showTitle = first, onEdit = onEdit) else Cleaning(cleaning)
                }
            }
        transcript == null -> Hint("Transcribing…", "The clean version follows as soon as Whisper is done.")
        transcript.isBlank() -> Hint("No speech recognised", "The recording is kept with the note.")
        c.cleanup != null || !gemmaInstalled -> {
            Text(transcript, tap, fontSize = 15.sp, lineHeight = 24.sp, color = Ink.TextBody)
            Text(when {
                c.cleanup?.startsWith(CleanupStep.NO_CLEANUP) == true -> "Exact words, as you chose: this type has no AI cleanup."
                gemmaInstalled -> "Kept your exact words: the clean version didn't pass the check."
                else -> "No cleanup model on this phone yet."
            }, fontSize = 12.sp, color = Ink.TextDim)
        }
        else -> Cleaning(cleaning)
    }
}

/**
 * The exact words, typed out letter by letter like an AI answer: quick (well under a second to
 * catch up), with a small amber cursor while it types. Words already shown are never typed again.
 */
@Composable
private fun TypeText(text: String, id: String, animate: Boolean, final: Boolean, revealed: MutableMap<String, Int>, onDone: (String) -> Unit) {
    val shown = if (animate) (revealed[id] ?: 0).coerceAtMost(text.length) else text.length
    LaunchedEffect(text, animate, final) {
        if (animate) {
            var n = revealed[id] ?: 0
            val step = maxOf(1, (text.length - n) / 45)
            while (n < text.length) { n = minOf(text.length, n + step); revealed[id] = n; delay(16) }
        }
        if (final) onDone(id)
    }
    // Caught up but Whisper is still working on the next piece: the cursor blinks like a terminal's,
    // and a muted line says what is going on, so the wait never looks like nothing is happening.
    val waiting = animate && !final && shown >= text.length
    val blink = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by blink.animateFloat(1f, 0f, infiniteRepeatable(keyframes {
        durationMillis = 1060
        1f at 0; 1f at 500; 0f at 530; 0f at 1030
    }), label = "blink")
    Text(androidx.compose.ui.text.buildAnnotatedString {
        append(text.substring(0, shown))
        if (shown < text.length || (animate && !final))
            withStyle(androidx.compose.ui.text.SpanStyle(color = Ink.Amber.copy(alpha = if (waiting) cursorAlpha else 1f))) { append(" ▍") }
    }, fontSize = 15.sp, lineHeight = 24.sp, color = Ink.TextBody)
    androidx.compose.animation.AnimatedVisibility(waiting, enter = fadeIn(tween(400)), exit = fadeOut(tween(200))) {
        Text("Slicing what you said into 30-second bites…", fontSize = 13.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            color = Ink.TextDim, modifier = Modifier.padding(top = 4.dp))
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
        fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Ink.Text.copy(alpha = 0.55f + 0.45f * (a - 0.35f) / 0.4f))
    listOf(0.92f, 0.78f, 0.6f).forEach { w ->
        Box(Modifier.fillMaxWidth(w).height(14.dp).clip(RoundedCornerShape(7.dp)).background(Ink.Raised.copy(alpha = a)))
    }
}

/**
 * Title and lines. Tap a line to edit the clean version; hold it to see the exact words it came from
 * (every AI line is traceable). Without editing (a note still busy), a tap shows the words.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CleanView(clean: String, transcript: String, showTitle: Boolean, onEdit: (() -> Unit)? = null) {
    val lines = remember(clean) { CleanupText.parseStored(clean) }
    val segments = remember(transcript) { CleanupText.segments(transcript) }
    var open by remember(clean) { mutableStateOf<Int?>(null) }
    lines.forEachIndexed { i, l ->
        if (l.title) { if (showTitle) Text(l.text, fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp, color = Ink.Text) }
        // A paragraph gets a little air above it, so it reads as one apart from a list.
        else Column(Modifier.fillMaxWidth().padding(top = if (l.para && i > 0) 6.dp else 0.dp).clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClickLabel = if (onEdit != null) "Edit" else "Show the exact words",
                onClick = { if (onEdit != null) onEdit() else open = if (open == i) null else i },
                onLongClickLabel = "Show the exact words",
                onLongClick = { open = if (open == i) null else i })
            .semantics { contentDescription = l.text }
            .padding(vertical = 2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (l.task) Box(Modifier.padding(top = 4.dp).size(16.dp).border(1.5.dp, Ink.TextDim, RoundedCornerShape(4.dp)))
                else if (!l.para) Box(Modifier.padding(top = 9.dp).size(5.dp).clip(CircleShape).background(Ink.TextBody))
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
        c.kind == "file" -> "The file is saved in your vault's Attachments."
        c.kind == "link" -> "The link and its page title are saved in the note."
        c.kind != "voice" -> "Your words, as you typed them."
        view == View.Clean -> "Your words, reordered. Nothing added. Tap to edit; hold a line to see what you said."
        else -> "Raw transcript is always kept with the audio. Tap to correct it."
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
/**
 * Drawn in one pass on a canvas, and the level is only read while drawing: the voice moves the
 * bars without redrawing (recomposing) anything else on the screen.
 */
@Composable
private fun Waveform(level: () -> Float, active: Boolean) {
    val target = remember { FloatArray(28) }
    val shown = remember { FloatArray(28) }
    val frame = remember { mutableIntStateOf(0) }
    LaunchedEffect(active) {
        while (true) {
            System.arraycopy(target, 1, target, 0, target.size - 1)
            target[target.size - 1] = if (active) level() else 0f
            frame.intValue++
            delay(70)
        }
    }
    val amber = Ink.Amber
    val grey = Ink.Bar
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(52.dp).semantics { contentDescription = "Input level" }) {
        @Suppress("UNUSED_EXPRESSION") frame.intValue // redraw on each new sample
        val w = 5.dp.toPx(); val gap = 4.dp.toPx(); val r = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
        val total = target.size * w + (target.size - 1) * gap
        var x = (size.width - total) / 2f
        for (i in target.indices) {
            shown[i] += (target[i] - shown[i]) * 0.55f // a little smoothing, so bars glide
            val v = shown[i]
            val h = (8f + 42f * v).dp.toPx()
            drawRoundRect(if (active) amber.copy(alpha = 0.55f + 0.45f * v) else grey,
                topLeft = androidx.compose.ui.geometry.Offset(x, (size.height - h) / 2f),
                size = androidx.compose.ui.geometry.Size(w, h), cornerRadius = r)
            x += w + gap
        }
    }
}

@android.annotation.SuppressLint("ModifierParameter") // the shared big button's modifier, not the row's
@Composable
private fun Controls(
    recordingHere: Boolean, paused: Boolean, elapsedMs: () -> Long, canUndo: Boolean, level: () -> Float, buttonModifier: Modifier,
    onRecord: () -> Unit, onPause: () -> Unit, onDiscard: () -> Unit, onUndo: () -> Unit,
) {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recordingHere) { while (recordingHere) { tick = System.currentTimeMillis(); delay(250) } }
    @Suppress("UNUSED_EXPRESSION") tick
    val ms = elapsedMs()
    val s = ms / 1000
    // Discard: short recordings go at once; from 20 s on it asks first, so a mis-tap can't wipe a long thought.
    var askDiscard by remember { mutableStateOf(false) }
    if (!recordingHere) askDiscard = false
    if (askDiscard) androidx.compose.material3.AlertDialog(
        onDismissRequest = { askDiscard = false }, containerColor = Ink.Panel,
        title = { Text("Discard this recording?") },
        text = { Text("It will not be transcribed or saved, and can't be recovered.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { askDiscard = false; onDiscard() }) { Text("Discard", color = Ink.Error) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { askDiscard = false }) { Text("Keep recording") } },
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.width(76.dp)) {
            // Only a time that means something: the running recording, or the length of the last one.
            if (recordingHere || ms > 0) Text("%d:%02d".format(s / 60, s % 60), fontFamily = Mono, fontSize = 14.sp, color = Ink.TextSoft,
                modifier = Modifier.semantics { contentDescription = "${s / 60} minutes ${s % 60} seconds" })
            if (recordingHere) Text("Discard", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ink.Error,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { if (elapsedMs() < 20_000) onDiscard() else askDiscard = true }.padding(vertical = 10.dp))
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
fun BigButton(recording: Boolean, paused: Boolean, level: () -> Float, size: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
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

/**
 * One button of the bottom row (Home and every note screen): an icon above its word, 52 dp tall.
 * [selected] (the screen you are on, e.g. Paste) is lighter; disabled ones are greyed out.
 */
@Composable
internal fun BarButton(label: String, icon: Int, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true,
                       onClick: () -> Unit) {
    val tint = when { !enabled -> Ink.Bar; selected -> Ink.TextStrong; else -> Ink.TextSoft }
    Surface(onClick = { if (enabled) onClick() }, shape = RoundedCornerShape(12.dp), color = if (selected) Ink.Raised else Ink.Panel,
        modifier = modifier.height(52.dp).semantics { this.selected = selected; role = Role.Button }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(painterResource(icon), null, Modifier.size(18.dp), tint = tint)
            Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = tint, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * Editing the note on screen: each part (the note, then each addition) is its own field, holding the
 * version that was on screen — the clean one, or the exact words. Save writes only what changed;
 * corrected exact words are cleaned again. [onSaved] says whether exact words changed.
 */
@Composable
private fun EditCard(sections: List<Capture>, clean: Boolean, focusId: String?, onCancel: () -> Unit, onSaved: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // What each part holds now, and whether it is its clean version.
    val start = remember { sections.filter { org.brain.capture.pipeline.NoteEdits.wordsEditable(it) }.associate { c ->
        val asClean = clean && c.clean != null
        c.id to ((if (asClean) CleanupText.editable(c.clean!!) else c.transcript.orEmpty()) to asClean)
    } }
    // The cursor starts at the end of each part, ready to add or correct.
    val texts = remember { mutableStateMapOf<String, androidx.compose.ui.text.input.TextFieldValue>().apply {
        start.forEach { (id, v) -> put(id, androidx.compose.ui.text.input.TextFieldValue(v.first, androidx.compose.ui.text.TextRange(v.first.length))) }
    } }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val changed = start.filter { (id, v) -> texts[id]?.text != v.first }

    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Scrolls with the cursor as you type (Enter at the bottom moves the view down), with a fine indicator on the right.
        val scroll = rememberScrollState()
        Column(Modifier.weight(1f).thinScrollbar(scroll).verticalScroll(scroll).padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            sections.forEachIndexed { i, c ->
                if (i > 0) Text("Added ${TIME.format(Date(c.createdAt))}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = Ink.TextDim, modifier = Modifier.padding(top = 8.dp))
                val v = texts[c.id]
                if (v == null) Text(c.transcript.orEmpty().ifBlank { "(a shared file)" }, fontSize = 15.sp, lineHeight = 24.sp, color = Ink.TextDim)
                else CursorFollowingTextField(v, { texts[c.id] = it },
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Ink.Well).padding(12.dp)
                        .then(if (c.id == focusId) Modifier.focusRequester(focus) else Modifier),
                    enabled = !busy,
                    textStyle = TextStyle(fontFamily = Inter, fontSize = 15.sp, lineHeight = 24.sp, color = Ink.TextBody))
            }
        }
        error?.let { Text(it, fontSize = 12.sp, color = Ink.Error) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(when {
                clean -> "Lines with \"- \" are bullets; your exact words stay as they are."
                Profiles.get(ctx, sections.firstOrNull()?.type).noCleanup ||
                    sections.none { it.kind == "voice" || it.clean != null || it.cleanup == TextCapture.AI_PENDING } -> "Your corrected words are saved as they are (no AI cleanup)."
                else -> "The clean version is made again from your corrected words."
            },
                fontSize = 12.sp, color = Ink.TextDim, modifier = Modifier.weight(1f).padding(end = 8.dp))
            androidx.compose.material3.TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel", color = Ink.TextSoft) }
            PillButton(if (busy) "Saving…" else "Save", enabled = changed.isNotEmpty() && !busy) {
                busy = true; error = null
                scope.launch {
                    var words = false
                    runCatching {
                        for ((id, v) in changed) {
                            val text = texts[id]?.text.orEmpty()
                            if (v.second) org.brain.capture.pipeline.NoteEdits.editClean(ctx, id, text)
                            else { org.brain.capture.pipeline.NoteEdits.editWords(ctx, id, text); words = true }
                        }
                    }.onSuccess { onSaved(words) }.onFailure { error = it.message ?: "Could not save" }
                    busy = false
                }
            }
        }
    }
}

/** Type or paste: write it, save it. After saving, the note shows like any other, with Undo. */
@Composable
private fun TextCard(mode: String, private: Boolean, typeId: String, appendTo: Capture? = null, onSaved: (Capture) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var field by rememberSaveable(mode, stateSaver = androidx.compose.ui.text.input.TextFieldValue.Saver) {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(""))
    }
    val text = field.text
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(mode) {
        if (mode == CaptureActivity.MODE_PASTE) {
            val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip
            val pasted = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
            field = androidx.compose.ui.text.input.TextFieldValue(pasted, androidx.compose.ui.text.TextRange(pasted.length))
        } else runCatching { focus.requestFocus() }
    }
    // No extra keyboard padding here: the screen already makes room for the keyboard.
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Scrolls with the cursor as you type, with a fine indicator on the right; the whole area stays tappable.
        val scroll = rememberScrollState()
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val visible = maxHeight
            Box(Modifier.fillMaxSize().thinScrollbar(scroll).verticalScroll(scroll).padding(end = 8.dp)) {
                if (text.isEmpty()) Text(if (mode == CaptureActivity.MODE_PASTE) "The clipboard is empty." else "Type anything…",
                    fontSize = 15.sp, color = Ink.TextDim)
                CursorFollowingTextField(field, { field = it },
                    Modifier.fillMaxWidth().heightIn(min = visible).focusRequester(focus),
                    textStyle = TextStyle(fontFamily = Inter, fontSize = 15.sp, lineHeight = 24.sp, color = Ink.TextBody))
            }
        }
        error?.let { Text(it, fontSize = 12.sp, color = Ink.Error) }
        Text(when {
            appendTo != null -> "Adds to this note"
            private -> "Kept on this phone only"
            else -> "Goes to your inbox as ${Profiles.get(ctx, appendTo?.type ?: typeId).name}"
        }, fontSize = 12.sp, color = Ink.TextDim)
        // Two ways to save: tidied by the AI with the note's type, or exactly as typed (fastest).
        // Without the AI model on the phone there is only the plain save.
        val aiAvailable = remember { GemmaHolder.modelFile(ctx) != null }
        var savingAi by remember { mutableStateOf<Boolean?>(null) }
        fun save(ai: Boolean) {
            busy = true; error = null; savingAi = ai
            scope.launch {
                runCatching { if (appendTo != null) TextCapture.append(ctx, appendTo, text.trim(), ai) else TextCapture.save(ctx, text.trim(), typeId, ai) }
                    .onSuccess { field = androidx.compose.ui.text.input.TextFieldValue(""); onSaved(it) }
                    .onFailure { error = it.message ?: "Could not save" }
                busy = false; savingAi = null
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The plain save is the main one (filled); the AI save is the outlined one, with the robot head.
            if (aiAvailable) SaveButton(if (savingAi == true) "Saving…" else "Save with AI", filled = false, robot = true,
                enabled = text.isNotBlank() && !busy, modifier = Modifier.weight(1f)) { save(ai = true) }
            SaveButton(if (savingAi == false) "Saving…" else if (aiAvailable) "Save, no AI" else "Save", filled = true, robot = false,
                enabled = text.isNotBlank() && !busy, modifier = Modifier.weight(1f)) { save(ai = false) }
        }
    }
}

/** A save button with the save icon: amber filled (Save with AI) or amber outline (Save, no AI). */
@Composable
private fun SaveButton(label: String, filled: Boolean, robot: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.4f
    val fg = if (filled) Ink.OnAmber else Ink.Amber.copy(alpha = alpha)
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(999.dp),
        color = if (filled) Ink.Amber.copy(alpha = alpha) else Color.Transparent,
        border = if (filled) null else androidx.compose.foundation.BorderStroke(1.5.dp, Ink.Amber.copy(alpha = alpha)),
        modifier = modifier.height(48.dp)) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            // The robot head marks the AI save; the plain disk is the save without AI.
            Icon(painterResource(if (robot) R.drawable.ic_robot else R.drawable.ic_save), null, Modifier.size(18.dp), tint = fg)
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = fg, modifier = Modifier.padding(start = 8.dp))
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
