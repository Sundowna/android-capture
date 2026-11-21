package org.brain.capture.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import org.brain.capture.CaptureState

class QuickRecordActivity : ComponentActivity() {
    // The "Talk" home-screen icon: one tap opens the capture screen and starts talking with the
    // default type; the next tap, while it records, stops it and shows that note (like Home's big button).
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val recording = CaptureState.isRecording
        if (recording) org.brain.capture.audio.CaptureService.send(this, org.brain.capture.audio.CaptureService.ACTION_STOP)
        startActivity(CaptureActivity.intent(this, org.brain.capture.types.Profiles.primary(this).id, start = !recording))
        finish()
    }
}
