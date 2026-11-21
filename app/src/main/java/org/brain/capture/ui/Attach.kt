package org.brain.capture.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.brain.capture.R
import org.brain.capture.share.AppScope
import org.brain.capture.vault.VaultUnavailableException
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The Attach sheet ("Add to this note"), like WhatsApp's paperclip: Gallery, Camera, Files & audio,
 * Contact, Link and Location. Each uses Android's own picker, then is saved like a share — a new note
 * when [appendTo] is null, otherwise added to that note. [onSaving] runs once something was picked,
 * [onDone] gets the saved capture's id or a message saying what happened instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachSheet(open: Boolean, appendTo: String?, onDismiss: () -> Unit, onSaving: () -> Unit, onDone: (id: String?, message: String?) -> Unit) {
    // Always composed (only the sheet itself comes and goes): the pickers' results must find their way back here.
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val saving by rememberUpdatedState(onSaving)
    val done by rememberUpdatedState(onDone)
    var linkOpen by rememberSaveable { mutableStateOf(false) }

    fun save(intent: Intent, source: String) {
        saving()
        AppScope.launch {
            val r = runCatching { Photos.saveShared(app, intent, source, appendTo) }
            withContext(Dispatchers.Main) {
                r.onSuccess { done(it, null) }.onFailure {
                    done(null, if (it is VaultUnavailableException) "${it.message}. It is kept on the phone and will be saved automatically later."
                        else "Could not save: ${it.message}")
                }
            }
        }
    }
    fun files(uris: List<Uri>, source: String) {
        if (uris.isEmpty()) { done(null, "Nothing picked"); return }
        save(Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).setType("*/*").apply {
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }, source)
    }
    fun text(text: String, source: String) = save(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), source)

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { files(it, "Gallery") }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { files(it, "Files") }
    val camera = rememberTakePhoto(onSaving = { saving() }, onDone = { id, msg -> done(id, msg) })
    // A phone number pick: Android hands over just that contact's name and number (no contacts permission).
    val contact = rememberLauncherForActivityResult(PickPhoneContact()) { uri ->
        if (uri == null) { done(null, "No contact picked"); return@rememberLauncherForActivityResult }
        val line = runCatching {
            app.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
                if (c.moveToFirst()) "Contact: ${c.getString(0)} · ${c.getString(1)}" else null
            }
        }.getOrNull()
        if (line == null) done(null, "Could not read that contact") else text(line, "Contacts")
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) addLocation(app, ::text) { done(null, it) } else done(null, "No location access, so nothing was added")
    }

    if (open) ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.Panel) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(if (appendTo != null) "Add to this note" else "New note from…", fontFamily = Grotesk,
                fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Ink.Text)
            val tiles = listOf(
                Tile("Gallery", R.drawable.ic_attach_gallery, Color(0xFF3B4A5C), Color(0xFF8DBDF5)) {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                Tile("Camera", R.drawable.t_camera, Color(0xFF5A5248), Color(0xFFF0C08A)) { camera(appendTo) },
                Tile("Files & audio", R.drawable.ic_attach_file, Color(0xFF4A4360), Color(0xFFC6B6F2)) { documents.launch(arrayOf("*/*")) },
                Tile("Contact", R.drawable.ic_attach_contact, Color(0xFF5A4450), Color(0xFFF2AECB)) { contact.launch(null) },
                Tile("Link", R.drawable.ic_link, Color(0xFF3A5458), Color(0xFF8ED6DC)) { linkOpen = true },
                Tile("Location", R.drawable.ic_attach_location, Color(0xFF5C3B38), Color(0xFFFF9C8C)) {
                    val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        .any { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
                    if (granted) addLocation(app, ::text) { done(null, it) }
                    else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                },
            )
            tiles.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { t ->
                        Column(Modifier.width(96.dp).clip(RoundedCornerShape(12.dp)).clickable { onDismiss(); t.onClick() }.padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(56.dp).clip(CircleShape).background(t.bg), contentAlignment = Alignment.Center) {
                                Icon(painterResource(t.icon), null, Modifier.size(24.dp), tint = t.fg)
                            }
                            Text(t.label, fontSize = 13.sp, color = Ink.TextSoft, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            Text("Saved to your vault's Attachments and added like a share. Undo takes it back.",
                fontSize = 12.sp, color = Ink.TextDim)
        }
    }

    // No "from …" for a link you typed yourself (the card would read "Link from Link").
    if (linkOpen) LinkDialog(onDismiss = { linkOpen = false }) { url -> linkOpen = false; text(url, "") }
}

private class Tile(val label: String, val icon: Int, val bg: Color, val fg: Color, val onClick: () -> Unit)

/** Picks one phone number from Contacts; the result URI can be read without the contacts permission. */
private class PickPhoneContact : androidx.activity.result.contract.ActivityResultContract<Unit?, Uri?>() {
    override fun createIntent(context: Context, input: Unit?) =
        Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data.takeIf { resultCode == android.app.Activity.RESULT_OK }
}

/** A link typed or pasted in; starts with what is on the clipboard when that is a link. */
@Composable
private fun LinkDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val ctx = LocalContext.current
    var url by rememberSaveable {
        val clip = runCatching { ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull()
        mutableStateOf(clip?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }.orEmpty())
    }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Ink.Panel,
        title = { Text("Add a link") },
        text = { OutlinedTextField(url, { url = it }, label = { Text("Link") }, placeholder = { Text("https://…") }, singleLine = true) },
        confirmButton = { TextButton(enabled = url.isNotBlank(), onClick = { onSave(url.trim()) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/**
 * Where the phone is now, as a line with a Google Maps link (opens on the phone and on the PC).
 * Only this one spot is saved; nothing is tracked.
 */
@android.annotation.SuppressLint("MissingPermission")
private fun addLocation(ctx: Context, save: (String, String) -> Unit, fail: (String) -> Unit) {
    val lm = ctx.getSystemService(LocationManager::class.java)
    AppScope.launch {
        val loc: Location? = withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine { cont ->
                val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
                if (provider == null) { cont.resumeWith(Result.success(null)); return@suspendCancellableCoroutine }
                lm.getCurrentLocation(provider, null, ctx.mainExecutor) { cont.resumeWith(Result.success(it)) }
            }
        } ?: runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
        withContext(Dispatchers.Main) {
            if (loc == null) { fail("Could not find where you are. Is location turned on?"); return@withContext }
            val lat = "%.6f".format(java.util.Locale.ROOT, loc.latitude); val lon = "%.6f".format(java.util.Locale.ROOT, loc.longitude)
            save("Location at ${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))}: https://maps.google.com/?q=$lat,$lon", "Location")
        }
    }
}
