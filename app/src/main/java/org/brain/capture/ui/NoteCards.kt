package org.brain.capture.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.brain.capture.R
import org.brain.capture.data.Capture
import org.brain.capture.pipeline.Notes

/**
 * One note as a card. Shows the note as it is in the vault right now (so edits made in Obsidian
 * appear here), with buttons to edit in Obsidian, add typed text, record more and copy.
 */
@Composable
fun NoteCard(c: Capture, title: String?, refresh: Int, canRecord: Boolean, onRecordMore: (Capture) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var view by remember(c.id) { mutableStateOf(Notes.cached(c.id)) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(c.id, c.lastActivityAt, refresh, reload) { view = Notes.load(ctx, c) }
    val text = view?.text ?: c.transcript.orEmpty()

    var copied by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            when {
                view == null && text.isEmpty() -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 8.dp))
                view?.missing == true -> Text("This note is no longer in the vault (deleted?). Showing the app's copy:\n\n$text",
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 8.dp))
                else -> SelectionContainer {
                    Text(text.ifBlank { "(empty note)" },
                        Modifier.padding(end = 8.dp).clickable(enabled = view?.fromVault == true) { editing = true })
                }
            }
            Text(
                "%s · %.0f s audio · transcription %.1f s · %.2f× · %d part(s)".format(
                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                        .format(java.util.Date(c.createdAt)),
                    c.audioSeconds, c.transcribeMs / 1000.0,
                    if (c.audioSeconds > 0) c.transcribeMs / 1000.0 / c.audioSeconds else 0.0, c.chunks),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    val path = c.notePath
                    if (path == null || !openInObsidian(ctx, path))
                        Toast.makeText(ctx, "Could not open Obsidian. Is it installed?", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Edit, "Open note in Obsidian") }
                IconButton(onClick = { typing = true }, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_text_add), "Add typed text to this note")
                }
                IconButton(onClick = { onRecordMore(c) }, enabled = canRecord, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_mic_add), "Record more for this note")
                }
                IconButton(onClick = {
                    // Only the note text, none of the timing details.
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Transcript", text))
                    copied = true
                    scope.launch { delay(1500); copied = false }
                }, modifier = Modifier.size(48.dp)) {
                    if (copied) Icon(Icons.Default.Check, "Copied", tint = MaterialTheme.colorScheme.primary)
                    else Icon(painterResource(R.drawable.ic_copy), "Copy text")
                }
            }
        }
    }

    if (editing) EditDialog(c, onDone = { editing = false; reload++ })
    if (typing) TypeDialog(c, onDone = { typing = false; reload++ })
}

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
