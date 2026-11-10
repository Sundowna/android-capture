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
import android.os.PowerManager
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
import org.brain.capture.data.Capture
import org.brain.capture.data.CaptureDb
import org.brain.capture.data.CaptureStates
import java.util.UUID
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
            ACTION_START -> startRecording(intent.getStringExtra(EXTRA_APPEND_TO), intent.getStringExtra(EXTRA_APPEND_LABEL))
            ACTION_STOP -> if (CaptureState.isRecording) stopRequested = true else if (!CaptureState.isBusy) stopSelf()
            ACTION_TOGGLE -> if (CaptureState.isRecording) stopRequested = true else startRecording()
            ACTION_PROCESS_PENDING -> processPending()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    /** [appendTo] is the capture id of the note to add to; [appendLabel] its path, for display. */
    private fun startRecording(appendTo: String? = null, appendLabel: String? = null) {
        if (CaptureState.isBusy) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            CaptureState.set(Phase.Failed("Microphone permission missing", null))
            stopSelf(); return
        }
        // Storage: refuse when there is no room left, warn when it's getting tight.
        val free = stagingDir(this).usableSpace
        if (free < MIN_FREE_TO_START) {
            CaptureState.set(Phase.Failed("Phone storage is full (${free / 1_000_000} MB free). Free up space, then record again.", null))
            stopSelf(); return
        }
        val minutesLeft = ((free - STOP_BELOW) / BYTES_PER_SECOND / 60).toInt().takeIf { it < 60 }
        val started = System.currentTimeMillis()
        CaptureState.set(Phase.Recording(started, appendLabel, minutesLeft))
        holdWakeLock()
        startForeground(NOTIF_ID, buildNotification(CaptureState.phase.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        vibrate()
        stopRequested = false

        val file = File(stagingDir(this), "${STAMP.format(Date(started))}.wav")
        CaptureState.current(file.path)
        recordJob = scope.launch(Dispatchers.IO) {
            // The row exists from the first second, so an interrupted recording (and its append
            // target) is never forgotten.
            CaptureDb.get(applicationContext).dao().upsert(Capture(
                id = UUID.randomUUID().toString(), createdAt = started, state = CaptureStates.RECORDING,
                stagingPath = file.path, appendTo = appendTo,
            ))
            val ok = runCatching {
                if (record(file)) CaptureDb.get(applicationContext).dao().byStaging(file.path)?.let {
                    CaptureDb.get(applicationContext).dao().upsert(it.copy(
                        warning = "Recording stopped early because the phone storage was full."))
                }
            }
                .onFailure { Log.e(TAG, "Recording failed", it) }
                .isSuccess
            CaptureState.level(0f)
            if (!ok) {
                CaptureState.set(Phase.Failed("Recording failed", file.takeIf { it.length() > 44 }?.path))
                CaptureState.current(null)
                finish(); return@launch
            }
            vibrate()
            runPipeline(file)
        }
    }

    @SuppressLint("MissingPermission")
    /** Records until stopped. Returns true if it had to stop early because storage ran out. */
    private fun record(file: File): Boolean {
        var storageFull = false
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
                    if (now - lastSync > 1000) {
                        wav.sync(); lastSync = now
                        if (file.parentFile!!.usableSpace < STOP_BELOW) { storageFull = true; stopRequested = true }
                    }
                }
            } finally {
                recorder.stop()
                recorder.release()
            }
        }
        CaptureState.set(Phase.SavingAudio)
        return storageFull
    }

    /** Transcribes and saves every recording still waiting in staging (after an interruption or failure). */
    private fun processPending() {
        if (CaptureState.isBusy) return
        val waiting = pendingRecordings(this)
        if (waiting.isEmpty()) { if (!CaptureState.isBusy) stopSelf(); return }
        CaptureState.set(Phase.LoadingModel)
        startForeground(NOTIF_ID, buildNotification(CaptureState.phase.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        holdWakeLock()
        scope.launch { runPipeline(waiting.first()) }
    }

    /** Processes [first], then anything else left in staging, one at a time. Stops at the first failure. */
    private suspend fun runPipeline(first: File) {
        val pipeline = VoicePipeline(applicationContext)
        var next: File? = first
        val done = HashSet<String>()
        while (next != null) {
            done += next.path
            CaptureState.current(next.path)
            pipeline.run(next)
            if (CaptureState.phase.value is Phase.Failed) break
            next = pendingRecordings(this).firstOrNull { it.path !in done }
        }
        CaptureState.current(null)
        finish()
    }

    private var wakeLock: PowerManager.WakeLock? = null

    /** Keeps the CPU awake while recording and transcribing with the screen off (max. 1 hour). */
    private fun holdWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BrainCapture:capture")
            .apply { setReferenceCounted(false); acquire(60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun finish() {
        releaseWakeLock()
        // Leave the final state (saved / failed) visible as a normal, dismissable notification.
        stopForeground(STOP_FOREGROUND_DETACH)
        notificationManager().notify(NOTIF_ID, buildNotification(CaptureState.phase.value))
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
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
            is Phase.Recording -> b.setContentTitle(if (p.appendTo != null) "Recording · adding to note" else "Recording")
                .setContentText(p.minutesLeft?.let { "Storage low: about $it min of recording left" })
                .setUsesChronometer(true).setWhen(p.startedAtMs).setShowWhen(true).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Stop", serviceIntent(ACTION_STOP)).build())
            Phase.SavingAudio -> b.setContentTitle("Saving recording…").setProgress(0, 0, true).setOngoing(true)
            Phase.LoadingModel -> b.setContentTitle("Preparing transcription…").setProgress(0, 0, true).setOngoing(true)
            is Phase.Transcribing -> b.setContentTitle("Transcribing")
                .setContentText(if (p.total > 1) "Part ${p.done + 1} of ${p.total}" else null)
                .setProgress(p.total, p.done, p.total <= 1).setOngoing(true)
            Phase.WritingVault -> b.setContentTitle("Saving to vault…").setProgress(0, 0, true).setOngoing(true)
            is Phase.Saved -> b.setContentTitle(when {
                    p.warning != null -> "Saved, with a warning"
                    p.appended -> "✓ Added to note"
                    else -> "✓ Saved"
                })
                .setContentText(p.warning ?: p.notePath)
                .setAutoCancel(true).setTimeoutAfter(4000)
            is Phase.Failed -> b.setContentTitle("⚠ ${p.message}")
                .setContentText(if (p.wavPath != null) "Recording kept. Tap Retry." else null)
                .setAutoCancel(true)
                .apply {
                    // Retry goes through the app: a microphone-type foreground service may only be
                    // started while the app is visible (Android 14+).
                    if (p.wavPath != null) addAction(Notification.Action.Builder(null, "Retry",
                        PendingIntent.getActivity(this@CaptureService, 1,
                            Intent(this@CaptureService, MainActivity::class.java).putExtra(EXTRA_RETRY, true),
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
        /** Recording needs ~32 KB per second; keep a safety margin for the vault copy. */
        private const val BYTES_PER_SECOND = 32_000L
        private const val MIN_FREE_TO_START = 50_000_000L
        private const val STOP_BELOW = 20_000_000L
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_PROCESS_PENDING = "process_pending"
        const val EXTRA_RETRY = "retry"
        const val EXTRA_APPEND_TO = "append_to"
        const val EXTRA_APPEND_LABEL = "append_label"
        private val STAMP = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.ROOT)

        fun stagingDir(ctx: Context): File = File(ctx.filesDir, "staging").apply { mkdirs() }

        /** Recordings in staging that are not being recorded or processed right now. */
        fun pendingRecordings(ctx: Context): List<File> {
            val busyWith = CaptureState.currentFile
            return stagingDir(ctx).listFiles { f -> f.extension == "wav" && f.length() > 44 && f.path != busyWith }
                ?.sortedBy { it.name }.orEmpty()
        }

        fun send(ctx: Context, action: String, extra: Intent.() -> Unit = {}) {
            val i = Intent(ctx, CaptureService::class.java).setAction(action).apply(extra)
            // Stop only ever goes to a service that is already in the foreground.
            if (action == ACTION_STOP) ctx.startService(i) else ctx.startForegroundService(i)
        }
    }
}
