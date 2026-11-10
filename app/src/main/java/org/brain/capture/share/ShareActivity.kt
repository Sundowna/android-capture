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
import org.brain.capture.ui.CaptureTheme
import org.brain.capture.ui.openInObsidian
import org.brain.capture.vault.VaultUnavailableException
import java.text.DateFormat
import java.util.Date

/** What the share panel shows. */
private sealed interface ShareUi {
    data class Saving(val what: String, val progress: Float?) : ShareUi
    data class Duplicate(val staged: Capture, val earlier: Capture) : ShareUi
    data class Saved(val notePath: String) : ShareUi
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
        ui.value = runCatching { ShareUi.Saved(Shares.save(app, staged.id)) }.getOrElse {
            val msg = if (it is VaultUnavailableException) "${it.message}." else (it.message ?: "Saving failed")
            ShareUi.Failed("$msg It is kept on the phone and will be saved automatically later.", kept = true)
        }
        Shares.resumePending(app) // anything older still waiting
    }

    @Composable
    private fun Panel() {
        val state by ui.collectAsState()
        val shown = remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown.value = true }
        LaunchedEffect(state) { if (state is ShareUi.Saved) { delay(1200); finish() } }

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
                                Text("✓ Saved", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                Text(s.notePath.substringBeforeLast('/'), style = MaterialTheme.typography.bodySmall)
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
