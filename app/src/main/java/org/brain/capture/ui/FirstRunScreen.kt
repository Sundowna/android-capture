package org.brain.capture.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import org.brain.capture.Prefs
import org.brain.capture.R
import org.brain.capture.transcribe.ModelStore

/** Shown until the vault folder is chosen and the speech model is installed. */
@Composable
fun FirstRunScreen(onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val models = remember { ModelStore(ctx) }
    val scope = rememberCoroutineScope()
    var vault by remember { mutableStateOf(prefs.vaultUri) }
    var modelReady by remember { mutableStateOf(models.isInstalled()) }
    var progress by remember { mutableStateOf<Float?>(-1f) } // -1 = not importing
    var error by remember { mutableStateOf<String?>(null) }

    val pickVault = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            prefs.vaultUri = uri; vault = uri; onChanged()
        }
    }
    val pickModel = rememberLauncherForActivityResult(OpenInDownloads()) { uri: Uri? ->
        if (uri != null) scope.launch {
            error = null; progress = null
            runCatching { withContext(Dispatchers.IO) { models.import(uri) { p -> scope.launch { progress = p } } } }
                .onFailure { error = it.message }
            progress = -1f
            modelReady = models.isInstalled(); onChanged()
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_mic), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
        Text("Capture anything into your vault", fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
        Text("Speak, or share from any app. Everything is transcribed on this phone and saved as Markdown in your Obsidian inbox.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Step 1: the vault folder.
        Step(number = 1, done = vault != null, active = vault == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Your vault", style = MaterialTheme.typography.titleMedium)
                    Text(vault?.lastPathSegment?.substringAfter(':') ?: "The Obsidian folder new notes should go to",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (vault != null) TextButton(onClick = { pickVault.launch(null) }) { Text("Change") }
            }
            if (vault == null) Button(onClick = { pickVault.launch(null) }, Modifier.fillMaxWidth().height(48.dp)) { Text("Choose folder") }
        }

        // Step 2: the speech model.
        Step(number = 2, done = modelReady, active = vault != null && !modelReady) {
            Text("Speech model", style = MaterialTheme.typography.titleMedium)
            Text("243 MB, once. The app itself never goes online.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!modelReady) {
                OutlinedButton(onClick = {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ModelStore.MODEL_URL))) }
                        .onFailure { error = "No browser found to download the model." }
                }, Modifier.fillMaxWidth().height(48.dp)) { Text("Download in browser") }
                Button(enabled = progress == -1f, onClick = { pickModel.launch(arrayOf("application/zip", "application/octet-stream")) },
                    modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Import downloaded file") }
                when (val p = progress) {
                    -1f -> Text("The file is checked against its official fingerprint before it is installed.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> {
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        Text("Installing… ${(p * 100).toInt()} %", style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(R.drawable.ic_lock), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("No account, no cloud, no tracking. Only your own sync tool moves notes off the phone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Step(number: Int, done: Boolean, active: Boolean, content: @Composable () -> Unit) {
    val b = brand()
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp,
        border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primaryContainer) else null) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(
                when { done -> b.successBg; active -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.surfaceVariant }),
                contentAlignment = Alignment.Center) {
                if (done) Icon(painterResource(R.drawable.ic_check), "Done", Modifier.size(17.dp), tint = b.success)
                else Text("$number", fontWeight = FontWeight.Bold,
                    color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
    }
}
