package org.brain.capture.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.brain.capture.data.Capture
import org.brain.capture.data.CaptureDb
import org.brain.capture.share.AppScope
import org.brain.capture.share.Shares
import org.brain.capture.vault.VaultUnavailableException
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Photo button. The phone's own camera app takes the picture (Brain needs no camera
 * permission); Brain then saves it the way it saves a shared image: copied into staging first,
 * then into the vault's Attachments, as a new note or added to the note on screen.
 */
object Photos {
    /** Shown as where the photo came from: "Shared from Camera", "Added 14:32 · shared from Camera". */
    const val SOURCE = "Camera"
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss", Locale.ROOT)
    private const val DAY_MS = 24 * 3600_000L

    /** An empty file in the cache for the camera app to write into. Clears leftovers older than a day. */
    fun newTarget(ctx: Context): File {
        val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
        val old = System.currentTimeMillis() - DAY_MS
        dir.listFiles()?.filter { it.lastModified() < old }?.forEach { it.delete() }
        // The file name becomes the photo's name in the vault ("2026-11-21 14.32.05 Photo.jpg").
        return File(dir, "${LocalDateTime.now().format(STAMP)} Photo.jpg")
    }

    fun uri(ctx: Context, f: File) = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

    /**
     * Saves what an Attach tile or the Photo button picked, the way a share is saved: a new note,
     * or added to the note [appendTo]. [intent] is an ACTION_SEND / SEND_MULTIPLE like a share's.
     * Returns the new capture's id.
     */
    suspend fun saveShared(ctx: Context, intent: Intent, sourceName: String, appendTo: String?): String {
        val staged = Shares.stage(ctx, intent, ctx.packageName, sourceName) { }
        if (appendTo != null) CaptureDb.get(ctx).dao().setAppendTo(staged.id, appendTo)
        Shares.save(ctx, staged.id)
        return staged.id
    }

    /** A photo note or addition made with the Photo button. */
    fun isPhoto(c: Capture?): Boolean = c?.kind == "file" &&
        runCatching { JSONObject(c.details ?: "{}").optString("source") == SOURCE }.getOrDefault(false)

    /**
     * Saves the photo: a new note, or added to the note [appendTo]. Returns the new capture's id.
     * If the vault can't be written now, the staged copy stays and is saved automatically later.
     */
    suspend fun save(ctx: Context, file: File, appendTo: String?): String {
        val intent = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri(ctx, file))
        val staged = Shares.stage(ctx, intent, ctx.packageName, SOURCE) { }
        file.delete() // the staged copy holds it now
        if (appendTo != null) CaptureDb.get(ctx).dao().setAppendTo(staged.id, appendTo)
        Shares.save(ctx, staged.id)
        return staged.id
    }
}

/**
 * Photo from outside the app (widget, 1x1 widget, app-icon shortcut): the phone's camera, then the
 * share panel, as if the photo had been shared to Brain — pick a note to add it to, and offer a
 * voice comment. Inside the app the Photo buttons keep their own flow ([rememberTakePhoto]).
 */
class PhotoActivity : androidx.activity.ComponentActivity() {
    private var pending: String? = null
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pending?.let(::File)
        pending = null
        if (f != null && ok && f.exists() && f.length() > 0L) {
            startActivity(Intent(this, org.brain.capture.share.ShareActivity::class.java)
                .setAction(Intent.ACTION_SEND).setType("image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, Photos.uri(this, f))
                .putExtra(org.brain.capture.share.ShareActivity.EXTRA_SOURCE_NAME, Photos.SOURCE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } else {
            f?.delete()
            android.widget.Toast.makeText(this, "No photo taken", android.widget.Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        pending = savedInstanceState?.getString(KEY_PENDING)
        if (savedInstanceState != null) return // back from the camera after Android recreated this screen
        val f = Photos.newTarget(this)
        pending = f.path
        runCatching { camera.launch(Photos.uri(this, f)) }.onFailure {
            pending = null; f.delete()
            android.widget.Toast.makeText(this, "No camera app found on this phone", android.widget.Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onSaveInstanceState(outState: android.os.Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PENDING, pending)
    }

    companion object {
        private const val KEY_PENDING = "pending"
        fun intent(ctx: Context): Intent = Intent(ctx, PhotoActivity::class.java)
            .setAction(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}

/**
 * Opens the phone's camera app; returns the function to call, with the note to add to (or null
 * for a new note). [onSaving] runs once a photo came back; [onDone] gets the saved capture's id,
 * or a message saying what happened instead ("No photo taken", a vault problem, …).
 */
@Composable
fun rememberTakePhoto(onSaving: () -> Unit, onDone: (id: String?, message: String?) -> Unit): (appendTo: String?) -> Unit {
    val ctx = LocalContext.current
    val saving by rememberUpdatedState(onSaving)
    val done by rememberUpdatedState(onDone)
    // Kept if Android recreates the app while the camera is open.
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pending?.let(::File)
        val appendTo = target
        pending = null; target = null
        if (f == null) return@rememberLauncherForActivityResult
        if (!ok || !f.exists() || f.length() == 0L) { f.delete(); done(null, "No photo taken"); return@rememberLauncherForActivityResult }
        saving()
        val app = ctx.applicationContext
        // App-wide scope: saving finishes even if this screen goes away.
        AppScope.launch {
            val r = runCatching { Photos.save(app, f, appendTo) }
            withContext(Dispatchers.Main) {
                r.onSuccess { done(it, null) }.onFailure {
                    done(null, if (it is VaultUnavailableException) "${it.message}. The photo is kept on the phone and will be saved automatically later."
                        else "Could not save the photo: ${it.message}")
                }
            }
        }
    }
    return { appendTo ->
        val f = Photos.newTarget(ctx)
        pending = f.path; target = appendTo
        runCatching { launcher.launch(Photos.uri(ctx, f)) }.onFailure {
            pending = null; target = null; f.delete()
            done(null, "No camera app found on this phone")
        }
    }
}
