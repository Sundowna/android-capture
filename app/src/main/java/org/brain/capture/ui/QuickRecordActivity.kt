package org.brain.capture.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import org.brain.capture.CaptureState

class QuickRecordActivity : ComponentActivity() {
    // The "Talk" home-screen icon: one tap opens the capture screen and starts talking with the
    // first widget type; while something records, it opens that recording instead.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(CaptureActivity.intent(this, org.brain.capture.types.Profiles.primary(this).id,
            start = !CaptureState.isRecording))
        finish()
    }
}
