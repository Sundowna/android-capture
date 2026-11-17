package org.brain.capture.ui

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.brain.capture.CaptureState
import org.brain.capture.R
import org.brain.capture.audio.CaptureService
import org.brain.capture.data.CaptureDb
import org.brain.capture.data.CaptureTypes

/**
 * Quick settings tile: one tap from anywhere, the lock screen included, opens the capture screen
 * and starts talking; a tap while recording stops and saves. The subtitle shows the last capture.
 */
class BrainTile : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { s ->
            s.launch { CaptureState.recording.collect { render() } }
        }
    }

    override fun onStopListening() {
        scope?.cancel(); scope = null
        super.onStopListening()
    }

    private suspend fun render() {
        val tile = qsTile ?: return
        val r = CaptureState.recording.value
        val last = if (r == null) withContext(Dispatchers.IO) { runCatching { CaptureDb.get(applicationContext).dao().latest() }.getOrNull() } else null
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_mic)
        tile.state = if (r != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when {
            r != null && r.paused -> "Paused · tap to save"
            r != null -> "Recording · tap to save"
            last != null -> BrainWidget.describe(this, last)
            else -> "Tap to talk"
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (CaptureState.isRecording) { CaptureService.send(this, CaptureService.ACTION_STOP); return }
        val open = { openCapture() }
        if (isLocked) unlockAndRun(open) else open()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openCapture() {
        val intent = CaptureActivity.intent(this, org.brain.capture.types.Profiles.primary(this).id, start = true)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(
            PendingIntent.getActivity(this, 21, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
    }
}
