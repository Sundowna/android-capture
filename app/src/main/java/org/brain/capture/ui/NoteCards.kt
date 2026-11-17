package org.brain.capture.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.brain.capture.LiveText
import org.brain.capture.Phase
import org.brain.capture.R
import org.brain.capture.audio.CaptureService
import org.brain.capture.data.Capture
import org.brain.capture.pipeline.Notes
import org.brain.capture.share.ShareParse
import org.json.JSONObject
import java.io.File

private val CardShape = RoundedCornerShape(18.dp)

/** "just now", "2 min. ago", "1 hr. ago", "Yesterday", "3 Nov". */
fun relativeTime(ms: Long): String {
    val now = System.currentTimeMillis()
    if (now - ms < 60_000) return "just now"
    return DateUtils.getRelativeTimeSpanString(ms, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
}

private fun duration(sec: Double): String { val s = sec.toLong(); return "%d:%02d".format(s / 60, s % 60) }

/** The coloured square at the start of a card, telling at a glance what kind of capture it is. */
@Composable
private fun KindTile(kind: String, saved: Boolean, private: Boolean = false) {
    val b = brand()
    val (bg, fg, icon) = when {
        saved -> Triple(b.successBg, b.success, R.drawable.ic_check)
        private -> Triple(Ink.Raised, Ink.TextStrong, R.drawable.ic_lock)
        kind == "link" -> Triple(b.linkBg, b.link, R.drawable.ic_link)
        kind == "text" -> Triple(b.voiceBg, b.voice, R.drawable.ic_text)
        kind == "file" -> Triple(b.fileBg, b.file, R.drawable.ic_doc)
        else -> Triple(b.voiceBg, b.voice, R.drawable.ic_mic)
    }
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(bg), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(16.dp), tint = fg)
    }
}

@Composable
private fun Meta(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)

/**
 * One note as a card. Shows the note as it is in the vault right now (edits made in Obsidian
 * appear here). [justSaved] turns the header green for a few seconds after saving.
 */
@Composable
fun NoteCard(c: Capture, refresh: Int, justSaved: Boolean, canRecord: Boolean, onRecordMore: (Capture) -> Unit, onOpen: (Capture) -> Unit = {}) {
    val ctx = LocalContext.current
    val b = brand()
    var view by remember(c.id) { mutableStateOf(Notes.cached(c.id)) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(c.id, c.lastActivityAt, refresh, reload) { view = Notes.load(ctx, c) }
    val text = view?.text ?: c.transcript.orEmpty()
    val details = remember(c.details) { runCatching { JSONObject(c.details ?: "{}") }.getOrDefault(JSONObject()) }
    val source = details.optString("source").ifEmpty { null }
    val folder = c.notePath?.substringBeforeLast('/') ?: ""

    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val border by animateColorAsState(if (justSaved) b.success.copy(alpha = 0.5f) else Color.Transparent, label = "b")

    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp,
        border = BorderStroke(1.5.dp, border), modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Header: kind + where/when, or the fresh "saved" confirmation.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(end = 8.dp)) {
                KindTile(c.kind, justSaved, c.isPrivate)
                if (justSaved) Meta(if (c.isPrivate) "Kept on this phone · just now" else "Saved to $folder · just now", b.success)
                else Meta(when {
                    c.isPrivate -> "Private · on this phone · ${relativeTime(c.lastActivityAt)}" +
                        (if (c.kind == "voice") " · ${duration(c.audioSeconds)}" else "")
                    c.type != "idea" -> "${org.brain.capture.types.Profiles.get(ctx, c.type).name} · ${relativeTime(c.lastActivityAt)}" +
                        (if (c.kind == "voice") " · ${duration(c.audioSeconds)}" else "")
                    else -> when (c.kind) {
                    "voice" -> "Voice · ${relativeTime(c.lastActivityAt)} · ${duration(c.audioSeconds)}"
                    "link" -> "Link${source?.let { " from $it" } ?: ""} · ${relativeTime(c.createdAt)}"
                    "text" -> "Text${source?.let { " from $it" } ?: ""} · ${relativeTime(c.createdAt)}"
                    else -> fileMeta(ctx, c, source)
                }})
            }

            if (view?.missing == true) Text("This note is no longer in the vault. Showing the app's copy.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 8.dp))
            when (c.kind) {
                "link" -> LinkBody(text)
                "file" -> {
                    SharedFiles(c)
                    val caption = text.lines().drop(1).joinToString("\n").trim() // first line is "Shared from …"
                    if (caption.isNotEmpty()) BodyText(caption, expanded, { expanded = it }, { editing = true }, view?.fromVault == true, null)
                }
                else -> when {
                    text.isNotBlank() -> BodyText(text, expanded, { expanded = it }, { editing = true }, view?.fromVault == true, { onOpen(c) })
                    view != null -> Text("(no speech recognised)", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 8.dp))
                }
            }
            if (expanded && c.kind == "voice") Meta("Transcribed in %.1f s · %.2f× real time · %d part(s)".format(
                c.transcribeMs / 1000.0, if (c.audioSeconds > 0) c.transcribeMs / 1000.0 / c.audioSeconds else 0.0, c.chunks))

            // Actions: quiet icons, always in the same order.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                // Delete sits apart on the left, away from the everyday actions.
                CardAction(painterResource(R.drawable.ic_delete), "Delete note") { deleting = true }
                Box(Modifier.weight(1f))
                if (!c.isPrivate) CardAction(painterResource(R.drawable.ic_pencil), "Open in Obsidian") {
                    val path = c.notePath
                    if (path == null || !openInObsidian(ctx, path))
                        Toast.makeText(ctx, "Could not open Obsidian. Is it installed?", Toast.LENGTH_SHORT).show()
                }
                if (!c.isPrivate) {
                    CardAction(painterResource(R.drawable.ic_text_add), "Add typed text") { typing = true }
                    CardAction(painterResource(R.drawable.ic_mic_add), "Record more for this note", enabled = canRecord) { onRecordMore(c) }
                }
                ShareAction(c)
                CopyAction(text.ifBlank { null })
            }
        }
    }

    if (editing) EditDialog(c, onDone = { editing = false; reload++ })
    if (typing) TypeDialog(c, onDone = { typing = false; reload++ })
    if (deleting) {
        val attached = if (c.kind == "voice") "its recordings" else if (c.kind == "file") "its files" else null
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete this note?") },
            text = { Text(if (c.isPrivate) "This private note${if (c.kind == "voice") " and its recording" else ""} will be deleted from this phone. It was never in your vault, so it can't be restored."
                else "The note${attached?.let { " and $it" } ?: ""} ${if (attached == null) "moves" else "move"} to the .trash folder in your vault, " +
                "where you can still restore them. Your sync tool will remove it on your other devices too.") },
            confirmButton = { TextButton(onClick = {
                deleting = false
                scope.launch {
                    runCatching { Notes.delete(ctx, c) }.onFailure {
                        Toast.makeText(ctx, "Could not delete: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

@Composable
/** Tap opens the note (to read it whole and talk more into it); long-press edits the text. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun BodyText(text: String, expanded: Boolean, onExpand: (Boolean) -> Unit, onEdit: () -> Unit, editable: Boolean, onOpen: (() -> Unit)?) {
    var overflows by remember(text) { mutableStateOf(false) }
    SelectionContainer {
        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis, onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
            modifier = Modifier.padding(end = 8.dp).then(
                if (onOpen != null) Modifier.combinedClickable(onClickLabel = "Open note", onClick = onOpen,
                    onLongClickLabel = "Edit note", onLongClick = if (editable) onEdit else null)
                else Modifier.clickable(enabled = editable, onClickLabel = "Edit note", onClick = onEdit)))
    }
    if (overflows || expanded) TextButton(onClick = { onExpand(!expanded) }, contentPadding = PaddingValues(0.dp)) {
        Text(if (expanded) "Show less" else "Show all", fontWeight = FontWeight.SemiBold)
    }
}

/** Link cards: page title in bold and the site, from the note's "# Title" and "<url>" lines. */
@Composable
private fun LinkBody(text: String) {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val title = lines.firstOrNull { it.startsWith("# ") }?.removePrefix("# ") ?: lines.firstOrNull() ?: ""
    val url = Regex("""https?://[^\s>]+""").find(text)?.value
    val extra = lines.filter { !it.startsWith("# ") && !it.startsWith("<http") && it != title }
        .joinToString("\n") { it.removePrefix("> ") }
    Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        url?.let { Meta(ShareParse.domain(it)) }
        if (extra.isNotBlank()) Text(extra, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun CardAction(icon: Painter, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) {
        Icon(icon, label, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.4f))
    }
}

/** Shares the note text plus its audio, photos and documents to another app. */
@Composable
private fun ShareAction(c: Capture) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    IconButton(enabled = !busy, onClick = {
        busy = true
        scope.launch {
            val bundle = Notes.shareBundle(ctx, c)
            busy = false
            val uris = bundle.files.map { (f, _) ->
                androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
            }
            val mimes = bundle.files.map { it.second }.distinct()
            val intent = when (uris.size) {
                0 -> android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                1 -> android.content.Intent(android.content.Intent.ACTION_SEND).setType(mimes[0])
                    .putExtra(android.content.Intent.EXTRA_STREAM, uris[0])
                else -> android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE)
                    .setType(if (mimes.size == 1) mimes[0] else mimes.map { it.substringBefore('/') }.distinct()
                        .singleOrNull()?.let { "$it/*" } ?: "*/*")
                    .putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
            }
            if (bundle.text.isNotBlank()) intent.putExtra(android.content.Intent.EXTRA_TEXT, bundle.text)
            if (uris.isNotEmpty()) {
                intent.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
                intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (bundle.text.isBlank() && uris.isEmpty()) {
                Toast.makeText(ctx, "Nothing to share in this note.", Toast.LENGTH_SHORT).show(); return@launch
            }
            val chooser = android.content.Intent.createChooser(intent, null).putExtra(
                android.content.Intent.EXTRA_EXCLUDE_COMPONENTS,
                arrayOf(android.content.ComponentName(ctx, org.brain.capture.share.ShareActivity::class.java)))
            ctx.startActivity(chooser)
        }
    }, modifier = Modifier.size(44.dp)) {
        Icon(painterResource(R.drawable.ic_share), "Share", Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (busy) 0.4f else 1f))
    }
}

@Composable
private fun CopyAction(text: String?) {
    if (text == null) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    IconButton(onClick = {
        // Only the note text, none of the details.
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Note", text))
        copied = true
        scope.launch { delay(1500); copied = false }
    }, modifier = Modifier.size(44.dp)) {
        if (copied) Icon(painterResource(R.drawable.ic_check), "Copied", Modifier.size(20.dp), tint = brand().success)
        else Icon(painterResource(R.drawable.ic_copy), "Copy text", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A recording that is not in the vault yet: queued, being transcribed (with the text filling in
 * part by part and real progress), or failed (with a retry).
 */
@Composable
fun ProgressCard(c: Capture, phase: Phase, live: LiveText?, processingId: String?, onRetry: () -> Unit) {
    val active = processingId == c.id
    val failed = !active && (c.state == "failed" || phase is Phase.Failed)
    val seconds = remember(c.stagingPath) { c.stagingPath?.let { ((File(it).length() - 44) / 32000.0).coerceAtLeast(0.0) } ?: 0.0 }
    val bar = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))

    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp,
        border = BorderStroke(2.dp, if (failed) MaterialTheme.colorScheme.error.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KindTile("voice", false)
                Meta("Voice · ${relativeTime(c.createdAt)} · ${duration(seconds)}" + (if (c.appendTo != null) " · adding to a note" else ""))
            }
            val text = live?.takeIf { it.captureId == c.id }?.text ?: c.transcript
            if (!text.isNullOrBlank()) Text(text, style = MaterialTheme.typography.bodyLarge)

            when {
                active && phase is Phase.Transcribing && phase.total > 1 -> {
                    LinearProgressIndicator(progress = { phase.done / phase.total.toFloat() }, modifier = bar)
                    Row {
                        Meta("Transcribing · part ${phase.done + 1} of ${phase.total}")
                        Box(Modifier.weight(1f))
                        phase.etaSeconds?.let { Meta("about ${CaptureService.formatEta(it)} left") }
                    }
                }
                active && phase.isBusy -> {
                    LinearProgressIndicator(bar)
                    Meta(phaseLabel(phase) ?: "")
                }
                failed -> {
                    Text((phase as? Phase.Failed)?.message ?: c.error ?: "Not saved yet.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Meta("The recording is safe on the phone.")
                    FilledTonalButton(onClick = onRetry) { Text("Try again") }
                }
                else -> {
                    LinearProgressIndicator(bar, color = MaterialTheme.colorScheme.outline)
                    Meta("Queued · starts when the one before is done")
                }
            }
        }
    }
}

// ---- Shared files ----

private data class FileInfo(val name: String, val mime: String, val size: Long, val pages: Int, val thumb: String)

private fun files(c: Capture): List<FileInfo> = runCatching {
    val a = JSONObject(c.details ?: return emptyList()).getJSONArray("files")
    (0 until a.length()).map { a.getJSONObject(it) }.map {
        FileInfo(it.optString("name"), it.optString("mime"), it.optLong("size"), it.optInt("pages", -1), it.optString("thumb"))
    }
}.getOrDefault(emptyList())

private fun fileMeta(ctx: android.content.Context, c: Capture, source: String?): String {
    val f = files(c)
    val from = source?.let { " from $it" } ?: ""
    val time = relativeTime(c.createdAt)
    fun size(b: Long) = android.text.format.Formatter.formatShortFileSize(ctx, b)
    return when {
        f.isEmpty() -> "File$from · $time"
        f.all { it.mime.startsWith("image/") } -> "${if (f.size == 1) "Photo" else "${f.size} photos"}$from · $time"
        f.size == 1 && f[0].mime == "application/pdf" ->
            "PDF$from" + (if (f[0].pages > 0) " · ${f[0].pages} pages" else "") + " · ${size(f[0].size)} · $time"
        f.size == 1 -> "File$from · ${size(f[0].size)} · $time"
        else -> "${f.size} files$from · $time"
    }
}

/** Previews of images and PDF first pages; document names below. Tapping opens the note. */
@Composable
private fun SharedFiles(c: Capture) {
    val ctx = LocalContext.current
    val list = files(c)
    if (list.isEmpty()) return
    val open = { c.notePath?.let { openInObsidian(ctx, it) }; Unit }
    val withThumb = list.filter { it.thumb.isNotEmpty() && File(it.thumb).exists() }
    if (withThumb.isNotEmpty()) Row(Modifier.padding(end = 8.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) { withThumb.forEach { Thumb(it, open) } }
    list.filter { !it.mime.startsWith("image/") }.forEach { f ->
        Text(f.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 8.dp).clickable(onClick = open))
    }
}

@Composable
private fun Thumb(f: FileInfo, onClick: () -> Unit) {
    var bmp by remember(f.thumb) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(f.thumb) {
        bmp = withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(f.thumb)?.asImageBitmap() }
    }
    val m = Modifier.height(if (f.mime == "application/pdf") 96.dp else 84.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
    bmp?.let { Image(it, contentDescription = f.name, modifier = m.aspectRatio(it.width.toFloat() / it.height.coerceAtLeast(1)),
        contentScale = ContentScale.Crop) } ?: Box(m.width(84.dp).background(MaterialTheme.colorScheme.surfaceVariant))
}

// ---- Dialogs ----

/** In-app editing. Saves only if the note was not changed elsewhere since the editor opened. */
@Composable
private fun EditDialog(c: Capture, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<String?>(null) }
    var body by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(c.id) {
        runCatching { Notes.openForEdit(ctx, c) }
            .onSuccess { (b, o) -> body = b; original = o }
            .onFailure { error = it.message }
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDone() },
        title = { Text("Edit note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (original == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth(), minLines = 6, enabled = !saving)
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = original != null && !saving, onClick = {
                saving = true; error = null
                scope.launch {
                    runCatching { Notes.saveEdit(ctx, c, original!!, body) }
                        .onSuccess { ok ->
                            if (ok) onDone()
                            else error = "This note was changed in Obsidian while you were editing, so nothing was " +
                                "saved. Your text is still here: copy it, or use the pencil to edit in Obsidian."
                        }
                        .onFailure { error = it.message }
                    saving = false
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDone) { Text("Cancel") } },
    )
}

/** Adds typed text to the end of the note. */
@Composable
private fun TypeDialog(c: Capture, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDone() },
        title = { Text("Add to note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 3, enabled = !saving,
                    placeholder = { Text("Type what you want to add…") })
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && !saving, onClick = {
                saving = true; error = null
                scope.launch {
                    runCatching { Notes.appendTyped(ctx, c, text) }.onSuccess { onDone() }.onFailure { error = it.message }
                    saving = false
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDone) { Text("Cancel") } },
    )
}
