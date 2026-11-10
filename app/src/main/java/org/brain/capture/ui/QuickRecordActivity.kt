package org.brain.capture.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import org.brain.capture.CaptureState
import org.brain.capture.audio.CaptureService

/**
 * The "Record" home-screen icon. Has no window: one tap starts recording, the next tap stops it.
 * Opens the main screen instead if setup (microphone permission) is not done yet.
 */
class QuickRecordActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when {
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            CaptureState.isRecording -> CaptureService.send(this, CaptureService.ACTION_STOP)
            !CaptureState.isBusy -> CaptureService.send(this, CaptureService.ACTION_START)
            // Busy transcribing an earlier recording: show progress rather than doing nothing.
            else -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}
