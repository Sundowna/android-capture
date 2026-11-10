package org.brain.capture.audio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.brain.capture.CaptureState
import org.brain.capture.Phase
import org.brain.capture.R
import org.brain.capture.pipeline.VoicePipeline
import org.brain.capture.ui.MainActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * Foreground service that owns the microphone. Records straight to a WAV file in app-private
 * staging, then runs the voice pipeline (transcribe → write to vault) on that file.
 */
class CaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recordJob: Job? = null
    @Volatile private var stopRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Keep the notification in step with the pipeline state.
        scope.launch {
            CaptureState.phase.collectLatest { p ->
                if (p !is Phase.Idle) notificationManager().notify(NOTIF_ID, buildNotification(p))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> if (CaptureState.isRecording) stopRequested = true else if (!CaptureState.isBusy) stopSelf()
            ACTION_TOGGLE -> if (CaptureState.isRecording) stopRequested = true else startRecording()
            ACTION_RETRY -> intent.getStringExtra(EXTRA_WAV)?.let { retry(File(it)) }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (CaptureState.isBusy) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            CaptureState.set(Phase.Failed("Microphone permission missing", null))
            stopSelf(); return
        }
        val started = System.currentTimeMillis()
        CaptureState.set(Phase.Recording(started))
        startForeground(NOTIF_ID, buildNotification(CaptureState.phase.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        vibrate()
        stopRequested = false

        val file = File(stagingDir(this), "${STAMP.format(Date(started))}.wav")
        recordJob = scope.launch(Dispatchers.IO) {
            val ok = runCatching { record(file) }
                .onFailure { Log.e(TAG, "Recording failed", it) }
                .isSuccess
            CaptureState.level(0f)
            if (!ok) {
                CaptureState.set(Phase.Failed("Recording failed", file.takeIf { it.length() > 44 }?.path))
                finish(); return@launch
            }
            vibrate()
            runPipeline(file)
        }
    }

    @SuppressLint("MissingPermission")
    private fun record(file: File) {
        val rate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate),
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone not available" }
        val buf = ShortArray(rate / 10) // 100 ms
        var lastSync = SystemClock.elapsedRealtime()
        WavWriter(file, rate).use { wav ->
            recorder.startRecording()
            try {
                while (!stopRequested) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n < 0) error("AudioRecord.read returned $n")
                    if (n == 0) continue
                    wav.write(buf, n)
                    var sum = 0.0
                    for (i in 0 until n) sum += buf[i] * buf[i].toDouble()
                    CaptureState.level((sqrt(sum / n) / 8000.0).coerceIn(0.0, 1.0).toFloat())
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastSync > 1000) { wav.sync(); lastSync = now }
                }
            } finally {
                recorder.stop()
                recorder.release()
            }
        }
        CaptureState.set(Phase.SavingAudio)
    }

    private fun retry(file: File) {
        if (CaptureState.isBusy || !file.exists()) return
        CaptureState.set(Phase.LoadingModel)
        startForeground(NOTIF_ID, buildNotification(CaptureState.phase.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        scope.launch { runPipeline(file) }
    }

    private suspend fun runPipeline(file: File) {
        VoicePipeline(applicationContext).run(file)
        finish()
    }

    private fun finish() {
        // Leave the final state (saved / failed) visible as a normal, dismissable notification.
        stopForeground(STOP_FOREGROUND_DETACH)
        notificationManager().notify(NOTIF_ID, buildNotification(CaptureState.phase.value))
        stopSelf()
    }

    override fun onDestroy() {
        stopRequested = true
        scope.cancel()
        super.onDestroy()
    }

    // ---- Notification ----

    private fun createChannel() {
        notificationManager().createNotificationChannel(
            NotificationChannel(CHANNEL, "Capture status", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(p: Phase): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        when (p) {
            is Phase.Recording -> b.setContentTitle("Recording")
                .setUsesChronometer(true).setWhen(p.startedAtMs).setShowWhen(true).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Stop", serviceIntent(ACTION_STOP)).build())
            Phase.SavingAudio -> b.setContentTitle("Saving recording…").setProgress(0, 0, true).setOngoing(true)
            Phase.LoadingModel -> b.setContentTitle("Preparing transcription…").setProgress(0, 0, true).setOngoing(true)
            is Phase.Transcribing -> b.setContentTitle("Transcribing")
                .setContentText(if (p.total > 1) "Part ${p.done + 1} of ${p.total}" else null)
                .setProgress(p.total, p.done, p.total <= 1).setOngoing(true)
            Phase.WritingVault -> b.setContentTitle("Saving to vault…").setProgress(0, 0, true).setOngoing(true)
            is Phase.Saved -> b.setContentTitle("✓ Saved").setContentText(p.notePath)
                .setAutoCancel(true).setTimeoutAfter(4000)
            is Phase.Failed -> b.setContentTitle("⚠ ${p.message}")
                .setContentText(if (p.wavPath != null) "Recording kept. Tap Retry." else null)
                .setAutoCancel(true)
                .apply {
                    // Retry goes through the app: a microphone-type foreground service may only be
                    // started while the app is visible (Android 14+).
                    if (p.wavPath != null) addAction(Notification.Action.Builder(null, "Retry",
                        PendingIntent.getActivity(this@CaptureService, 1,
                            Intent(this@CaptureService, MainActivity::class.java).putExtra(EXTRA_WAV, p.wavPath),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
                }
            Phase.Idle -> b.setContentTitle("Ready")
        }
        return b.build()
    }

    private fun serviceIntent(action: String, extra: Intent.() -> Unit = {}): PendingIntent =
        PendingIntent.getService(this, action.hashCode(),
            Intent(this, CaptureService::class.java).setAction(action).apply(extra),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun vibrate() = runCatching {
        getSystemService(VibratorManager::class.java).defaultVibrator
            .vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
    }

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 1
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_RETRY = "retry"
        const val EXTRA_WAV = "wav"
        private val STAMP = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.ROOT)

        fun stagingDir(ctx: Context): File = File(ctx.filesDir, "staging").apply { mkdirs() }

        fun send(ctx: Context, action: String, extra: Intent.() -> Unit = {}) {
            val i = Intent(ctx, CaptureService::class.java).setAction(action).apply(extra)
            // Stop only ever goes to a service that is already in the foreground.
            if (action == ACTION_STOP) ctx.startService(i) else ctx.startForegroundService(i)
        }
    }
}
