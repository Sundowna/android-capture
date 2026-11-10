package org.brain.capture.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.brain.capture.data.Capture
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import org.brain.capture.CaptureState
import org.brain.capture.R
import org.brain.capture.audio.CaptureService
import org.brain.capture.ui.PulsingDot
import org.brain.capture.ui.brand
import org.brain.capture.ui.CaptureTheme
import org.brain.capture.ui.openInObsidian
import org.brain.capture.vault.VaultUnavailableException
import java.text.DateFormat
import java.util.Date

/** What the share panel shows. */
private sealed interface ShareUi {
    data class Saving(val what: String, val progress: Float?) : ShareUi
    data class Duplicate(val staged: Capture, val earlier: Capture) : ShareUi
    /** [title]/[subtitle] say what was saved: the page title and site, the file name, the text. */
    data class Saved(val notePath: String, val captureId: String, val kind: String, val title: String, val subtitle: String) : ShareUi
    /** Recording a voice comment that is added to the note just saved. */
    data class Commenting(val saved: Saved, val stopped: Boolean = false) : ShareUi
    data class Failed(val message: String, val kept: Boolean) : ShareUi
}

/**
 * Target of Android's Share menu. Shows a small panel at the bottom while the shared link, text or
 * files are saved, then closes itself. The work runs in [AppScope], so it finishes even if the
 * panel is closed early.
 */
class ShareActivity : ComponentActivity() {
    private val ui = MutableStateFlow<ShareUi>(ShareUi.Saving("Saving…", null))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) start(intent)
        setContent { CaptureTheme { Panel() } }
    }

    private fun start(intent: Intent) {
        val what = when {
            intent.action == Intent.ACTION_SEND_MULTIPLE -> "Saving files…"
            intent.type?.startsWith("text/") == true && !intent.hasExtra(Intent.EXTRA_STREAM) -> "Saving…"
            intent.type == "application/pdf" -> "Saving PDF…"
            intent.type?.startsWith("image/") == true -> "Saving image…"
            else -> "Saving file…"
        }
        ui.value = ShareUi.Saving(what, null)
        val source = referrer?.takeIf { it.scheme == "android-app" }?.host
        val app = applicationContext
        AppScope.launch {
            val staged = runCatching {
                Shares.stage(app, intent, source) { p -> ui.value = ShareUi.Saving(what, p) }
            }.getOrElse { ui.value = ShareUi.Failed(it.message ?: "Could not read the shared content", kept = false); return@launch }
            if (staged.kind == "link") ui.value = ShareUi.Saving("Saving link…", null)
            else if (staged.kind == "text") ui.value = ShareUi.Saving("Saving text…", null)
            val earlier = Shares.duplicateOf(app, staged)
            if (earlier != null) { ui.value = ShareUi.Duplicate(staged, earlier); return@launch }
            save(staged)
        }
    }

    private suspend fun save(staged: Capture) {
        val app = applicationContext
        ui.value = runCatching { describe(Shares.save(app, staged.id), staged.id) }.getOrElse {
            val msg = if (it is VaultUnavailableException) "${it.message}." else (it.message ?: "Saving failed")
            ShareUi.Failed("$msg It is kept on the phone and will be saved automatically later.", kept = true)
        }
        Shares.resumePending(app) // anything older still waiting
    }

    /** What to show for a saved share, from its database row. */
    private suspend fun describe(notePath: String, id: String): ShareUi.Saved {
        val c = org.brain.capture.data.CaptureDb.get(applicationContext).dao().get(id)
        val lines = c?.transcript.orEmpty().lines().filter { it.isNotBlank() }
        val source = runCatching { org.json.JSONObject(c?.details ?: "{}").optString("source") }.getOrNull()?.ifEmpty { null }
        val folder = notePath.substringBeforeLast('/')
        return when (c?.kind) {
            "link" -> {
                val url = lines.firstOrNull { it.startsWith("http") }
                val title = lines.firstOrNull { !it.startsWith("http") } ?: url ?: "Link"
                ShareUi.Saved(notePath, id, "link", title, url?.let { ShareParse.domain(it) } ?: folder)
            }
            "text" -> ShareUi.Saved(notePath, id, "text", lines.joinToString(" ").take(120), "Text" + (source?.let { " from $it" } ?: ""))
            else -> ShareUi.Saved(notePath, id, "file", lines.firstOrNull() ?: "File",
                (if (lines.size > 1) "${lines.size} files" else "File") + (source?.let { " from $it" } ?: ""))
        }
    }

    private fun startComment(s: ShareUi.Saved) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        CaptureService.send(this, CaptureService.ACTION_START) {
            putExtra(CaptureService.EXTRA_APPEND_TO, s.captureId)
            putExtra(CaptureService.EXTRA_APPEND_LABEL, s.notePath)
        }
        ui.value = ShareUi.Commenting(s)
    }

    @Composable
    private fun Panel() {
        val state by ui.collectAsState()
        val recording by CaptureState.recording.collectAsState()
        val level by CaptureState.level.collectAsState()
        val shown = remember { mutableStateOf(false) }
        var touched by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown.value = true }
        // Saved: close by itself after a moment, unless the user is about to add a voice comment.
        LaunchedEffect(state, touched) {
            val s = state
            if (s is ShareUi.Saved && !touched) { delay(3500); if (ui.value === s) finish() }
            if (s is ShareUi.Commenting && s.stopped) { delay(1500); finish() }
        }
        // The comment recording ended (Stop here, in the notification or on the home screen).
        LaunchedEffect(recording) {
            val s = ui.value
            if (s is ShareUi.Commenting && !s.stopped && recording == null) {
                delay(300)
                if (CaptureState.recording.value == null) ui.value = s.copy(stopped = true)
            }
        }

        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                .clickable(remember { MutableInteractionSource() }, indication = null) { finish() },
            contentAlignment = androidx.compose.ui.Alignment.BottomCenter,
        ) {
            AnimatedVisibility(shown.value, enter = slideInVertically { it }) {
                Surface(
                    Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding()
                        .clickable(remember { MutableInteractionSource() }, indication = null) {},
                    shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp, shadowElevation = 8.dp,
                ) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        when (val s = state) {
                            is ShareUi.Saving -> {
                                Text(s.what, style = MaterialTheme.typography.titleMedium)
                                if (s.progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                                else LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                            }
                            is ShareUi.Saved -> {
                                SavedHeader(s)
                                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                                    android.content.pm.PackageManager.PERMISSION_GRANTED && !CaptureState.isRecording)
                                    OutlinedButton(onClick = { touched = true; startComment(s) },
                                        modifier = Modifier.fillMaxWidth().height(48.dp)) {
                                        Icon(painterResource(R.drawable.ic_mic), null, Modifier.size(18.dp))
                                        Text("  Add a voice comment")
                                    }
                            }
                            is ShareUi.Commenting -> {
                                SavedHeader(s.saved)
                                val r = recording
                                if (!s.stopped && r != null) {
                                    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                                    LaunchedEffect(r.startedAtMs) { while (true) { now = System.currentTimeMillis(); delay(200) } }
                                    val sec = ((now - r.startedAtMs) / 1000).coerceAtLeast(0)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        PulsingDot()
                                        Text("Recording your comment", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                        Text("%d:%02d".format(sec / 60, sec % 60), style = MaterialTheme.typography.titleMedium)
                                    }
                                    LinearProgressIndicator(progress = { level }, color = brand().record,
                                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                                    Button(onClick = { CaptureService.send(this@ShareActivity, CaptureService.ACTION_STOP) },
                                        modifier = Modifier.fillMaxWidth().height(56.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = brand().record, contentColor = Color.White)) {
                                        Text("Stop")
                                    }
                                } else if (s.stopped) {
                                    Text("Your comment will be added to this note.", style = MaterialTheme.typography.bodyMedium)
                                } else {
                                    Text("Starting microphone…", style = MaterialTheme.typography.bodyMedium)
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                            }
                            is ShareUi.Duplicate -> {
                                val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.earlier.createdAt))
                                Text("Already saved at $time", style = MaterialTheme.typography.titleMedium)
                                Text("You shared the same ${if (s.staged.kind == "link") "link" else "content"} earlier.",
                                    style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = {
                                        AppScope.launch { Shares.discard(applicationContext, s.staged) }
                                        s.earlier.notePath?.let { openInObsidian(this@ShareActivity, it) }
                                        finish()
                                    }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Open note") }
                                    Button(onClick = {
                                        ui.value = ShareUi.Saving("Saving…", null)
                                        AppScope.launch { save(s.staged) }
                                    }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Save again") }
                                }
                            }
                            is ShareUi.Failed -> {
                                Text("⚠ Not saved yet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                                Text(s.message, style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = { finish() }, Modifier.fillMaxWidth()) { Text("Close") }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        // Closing the panel on the duplicate question means "don't save again".
        if (isFinishing) (ui.value as? ShareUi.Duplicate)?.let { d -> AppScope.launch { Shares.discard(applicationContext, d.staged) } }
        super.onDestroy()
    }
}

/** Green confirmation plus a small card with what was saved (title and site, file name, or text). */
@Composable
private fun SavedHeader(s: ShareUi.Saved) {
    val b = brand()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(b.successBg), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_check), null, Modifier.size(19.dp), tint = b.success)
        }
        Column {
            Text(when (s.kind) { "link" -> "Link saved"; "text" -> "Text saved"; else -> "Saved" },
                style = MaterialTheme.typography.titleMedium)
            Text(s.notePath.substringBeforeLast('/'), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.background).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val (bg, fg, icon) = when (s.kind) {
            "link" -> Triple(b.linkBg, b.link, R.drawable.ic_link)
            "text" -> Triple(b.voiceBg, b.voice, R.drawable.ic_text)
            else -> Triple(b.fileBg, b.file, R.drawable.ic_doc)
        }
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(bg), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(16.dp), tint = fg)
        }
        Column(Modifier.weight(1f)) {
            Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(s.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

