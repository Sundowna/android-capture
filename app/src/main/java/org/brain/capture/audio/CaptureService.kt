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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.brain.capture.CaptureState
import org.brain.capture.Phase
import org.brain.capture.R
import org.brain.capture.RecordingInfo
import org.brain.capture.data.Capture
import org.brain.capture.data.CaptureDb
import org.brain.capture.data.CaptureStates
import org.brain.capture.data.CaptureTypes
import org.brain.capture.pipeline.VoicePipeline
import org.brain.capture.ui.MainActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.sqrt

/**
 * Foreground service that owns the microphone and the processing queue.
 * Recording and processing are independent: a new recording can start while earlier ones are
 * still being transcribed; finished recordings queue up and are processed one after another.
 */
class CaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var stopRequested = false
    @Volatile private var cancelRequested = false
    @Volatile private var pauseRequested = false
    private val procLock = Any()
    private var processingActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // One notification: the recording while there is one, otherwise the processing state.
        scope.launch {
            combine(CaptureState.recording, CaptureState.phase) { r, p -> r to p }.collectLatest { (r, p) ->
                if (r != null || p !is Phase.Idle) notificationManager().notify(NOTIF_ID, buildNotification(r, p))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent.getStringExtra(EXTRA_APPEND_TO), intent.getStringExtra(EXTRA_APPEND_LABEL),
                intent.getStringExtra(EXTRA_TYPE) ?: CaptureTypes.IDEA)
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_STOP -> if (CaptureState.isRecording) stopRequested = true else maybeFinish()
            ACTION_CANCEL -> if (CaptureState.isRecording) { cancelRequested = true; stopRequested = true } else maybeFinish()
            ACTION_TOGGLE -> if (CaptureState.isRecording) stopRequested = true else startRecording()
            ACTION_PROCESS_PENDING -> {
                if (CaptureState.phase.value is Phase.Failed) CaptureState.set(Phase.Idle)
                if (pendingRecordings(this).isNotEmpty()) ensureProcessing() else maybeFinish()
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground() = startForeground(NOTIF_ID,
        buildNotification(CaptureState.recording.value, CaptureState.phase.value),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

    @SuppressLint("MissingPermission")
    /** [appendTo] is the capture id of the note to add to; [appendLabel] its path, for display. */
    private fun startRecording(appendTo: String? = null, appendLabel: String? = null, type: String = CaptureTypes.IDEA) {
        if (CaptureState.isRecording) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            CaptureState.set(Phase.Failed("Microphone permission missing", null))
            maybeFinish(); return
        }
        // Storage: refuse when there is no room left, warn when it's getting tight.
        val free = stagingDir(this).usableSpace
        if (free < MIN_FREE_TO_START) {
            CaptureState.set(Phase.Failed("Phone storage is full (${free / 1_000_000} MB free). Free up space, then record again.", null))
            maybeFinish(); return
        }
        val minutesLeft = ((free - STOP_BELOW) / BYTES_PER_SECOND / 60).toInt().takeIf { it < 60 }
        val started = System.currentTimeMillis()
        val file = File(stagingDir(this), "${STAMP.format(Date(started))}.wav")
        val id = UUID.randomUUID().toString()
        CaptureState.recording(RecordingInfo(started, file.path, appendLabel, minutesLeft, captureId = id, type = type, appendTo = appendTo))
        holdWakeLock()
        goForeground()
        vibrate()
        // Load Gemma while the user talks, so cleanup can start as soon as the transcript is ready.
        org.brain.capture.cleanup.GemmaHolder.warm(applicationContext)
        stopRequested = false
        cancelRequested = false
        pauseRequested = false

        scope.launch(Dispatchers.IO) {
            // The row exists from the first second, so an interrupted recording (and its append
            // target) is never forgotten.
            val dao = CaptureDb.get(applicationContext).dao()
            dao.upsert(Capture(id = id, createdAt = started, state = CaptureStates.RECORDING,
                stagingPath = file.path, appendTo = appendTo, type = type))
            val ok = runCatching {
                val storageFull = record(file)
                dao.byStaging(file.path)?.let { c ->
                    dao.upsert(c.copy(state = CaptureStates.RECORDED,
                        warning = if (storageFull) "Recording stopped early because the phone storage was full." else c.warning))
                }
            }.onFailure { Log.e(TAG, "Recording failed", it) }.isSuccess
            CaptureState.level(0f)
            if (cancelRequested) {
                // Discarded: remove the audio before the queue could pick it up, and forget the row.
                file.delete()
                dao.byStaging(file.path)?.let { dao.upsert(it.copy(state = CaptureStates.DISCARDED)) }
                dao.deleteDiscarded()
                CaptureState.recording(null)
                vibrate()
                Log.i(TAG, "Recording discarded")
                maybeFinish(); return@launch
            }
            CaptureState.recording(null)
            vibrate()
            if (!ok && file.length() <= 44) {
                file.delete()
                CaptureState.set(Phase.Failed("The microphone could not be started.", null))
                maybeFinish(); return@launch
            }
            ensureProcessing()
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
        val buf = ShortArray(rate / 20) // 50 ms, for a lively level meter
        var lastSync = SystemClock.elapsedRealtime()
        WavWriter(file, rate).use { wav ->
            recorder.startRecording()
            try {
                while (!stopRequested) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n < 0) error("AudioRecord.read returned $n")
                    if (n == 0) continue
                    // Paused: keep the microphone open (no gap to restart) but write nothing.
                    if (pauseRequested) { CaptureState.level(0f); continue }
                    wav.write(buf, n)
                    var sum = 0.0
                    for (i in 0 until n) sum += buf[i] * buf[i].toDouble()
                    CaptureState.level((sqrt(sum / n) / 6000.0).coerceIn(0.0, 1.0).toFloat())
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
        return storageFull
    }

    /** Pause keeps the recording open; the paused time is left out of the audio and the timer. */
    private fun setPaused(on: Boolean) {
        val r = CaptureState.recording.value ?: return
        if (r.paused == on) return
        pauseRequested = on
        val now = System.currentTimeMillis()
        CaptureState.recording(if (on) r.copy(pausedAtMs = now)
            else r.copy(pausedAtMs = null, pausedTotalMs = r.pausedTotalMs + (now - (r.pausedAtMs ?: now))))
        vibrate()
    }

    /** Starts the processing queue unless it is already running. */
    private fun ensureProcessing() {
        synchronized(procLock) { if (processingActive) return; processingActive = true }
        holdWakeLock()
        goForeground()
        scope.launch {
            val pipeline = VoicePipeline(applicationContext)
            try {
                while (true) {
                    val next = pendingRecordings(this@CaptureService).firstOrNull() ?: break
                    pipeline.run(next)
                    if (CaptureState.phase.value is Phase.Failed) break
                }
                // Raw notes are saved and shown; now the clean versions, in the background.
                org.brain.capture.cleanup.CleanupStep.runPending(applicationContext)
                org.brain.capture.ui.BrainWidget.refresh(applicationContext)
            } finally {
                CaptureState.processing(null, null)
                synchronized(procLock) { processingActive = false }
            }
            // A recording may have finished in the very moment the queue ended.
            if (CaptureState.phase.value !is Phase.Failed && pendingRecordings(this@CaptureService).isNotEmpty()) ensureProcessing()
            else maybeFinish()
        }
    }

    /** Leaves the foreground once nothing is recording or processing. */
    private fun maybeFinish() {
        val busy = synchronized(procLock) { processingActive } || CaptureState.isRecording
        if (busy) return
        releaseWakeLock()
        // Leave the final state (saved / failed) visible as a normal, dismissable notification.
        stopForeground(STOP_FOREGROUND_DETACH)
        val p = CaptureState.phase.value
        if (p !is Phase.Idle) notificationManager().notify(NOTIF_ID, buildNotification(null, p))
        stopSelf()
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

    private fun buildNotification(r: RecordingInfo?, p: Phase): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (r != null) {
            return b.setContentTitle(when {
                    r.paused -> "Paused"
                    r.appendLabel != null -> "Recording · adding to note"
                    else -> "Recording"
                })
                .setContentText(r.minutesLeft?.let { "Storage low: about $it min of recording left" }
                    ?: if (CaptureState.isProcessing) "Earlier recording is being transcribed" else null)
                // Android 16+ shows this as a status-bar chip with the timer, so the recording is
                // visible from any app ("Live Update"); older versions ignore the request.
                .addExtras(android.os.Bundle().apply {
                    putBoolean("android.requestPromotedOngoing", true)
                    // No short text: then the chip shows the running timer.
                    if (r.paused) putString("android.shortCriticalText", "Paused")
                })
                .setUsesChronometer(!r.paused).setWhen(System.currentTimeMillis() - r.elapsedMs()).setShowWhen(!r.paused).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Stop", serviceIntent(ACTION_STOP)).build())
                .addAction(Notification.Action.Builder(null, "Discard", serviceIntent(ACTION_CANCEL)).build())
                .build()
        }
        when (p) {
            Phase.LoadingModel -> b.setContentTitle("Preparing transcription…").setProgress(0, 0, true).setOngoing(true)
            is Phase.Transcribing -> b.setContentTitle("Transcribing")
                .setContentText(listOfNotNull(
                    if (p.total > 1) "Part ${p.done + 1} of ${p.total}" else null,
                    p.etaSeconds?.let { "about ${formatEta(it)} left" }).joinToString(" · ").ifEmpty { null })
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
        const val ACTION_CANCEL = "cancel"
        const val ACTION_PROCESS_PENDING = "process_pending"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val EXTRA_TYPE = "type"
        const val EXTRA_RETRY = "retry"
        const val EXTRA_APPEND_TO = "append_to"
        const val EXTRA_APPEND_LABEL = "append_label"
        private val STAMP = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.ROOT)

        fun stagingDir(ctx: Context): File = File(ctx.filesDir, "staging").apply { mkdirs() }

        /** Recordings in staging that are not being recorded or processed right now, oldest first. */
        fun pendingRecordings(ctx: Context): List<File> {
            val recording = CaptureState.recording.value?.file
            val processing = CaptureState.processingFile
            return stagingDir(ctx).listFiles { f ->
                f.extension == "wav" && f.length() > 44 && f.path != recording && f.path != processing
            }?.sortedBy { it.name }.orEmpty()
        }

        fun formatEta(s: Int): String = if (s < 60) "${maxOf(5, (s + 4) / 5 * 5)} s" else "${(s + 30) / 60} min"

        fun send(ctx: Context, action: String, extra: Intent.() -> Unit = {}) {
            val i = Intent(ctx, CaptureService::class.java).setAction(action).apply(extra)
            // Stop only ever goes to a service that is already in the foreground.
            if (action in setOf(ACTION_STOP, ACTION_CANCEL, ACTION_PAUSE, ACTION_RESUME)) ctx.startService(i) else ctx.startForegroundService(i)
        }
    }
}
