package org.brain.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the processing pipeline (transcribe → vault) is doing. Recording is tracked separately
 * in [CaptureState.recording], so a new recording can start while an earlier one is processed.
 */
sealed interface Phase {
    data object Idle : Phase
    data object LoadingModel : Phase
    /**
     * [done] of [total] parts finished (real parts, never estimated). [etaSeconds] comes from how
     * long the finished parts took; null until there is something to measure.
     */
    data class Transcribing(val done: Int, val total: Int, val etaSeconds: Int? = null) : Phase
    data object WritingVault : Phase
    /** [captureId] is the card that changed (for an addition: the note it was added to). */
    data class Saved(val notePath: String, val warning: String? = null, val appended: Boolean = false,
                     val captureId: String? = null, val atMs: Long = System.currentTimeMillis()) : Phase
    data class Failed(val message: String, val wavPath: String?) : Phase
}

/** A recording in progress. [appendLabel] is the note's path when it will be added to a note. */
data class RecordingInfo(val startedAtMs: Long, val file: String, val appendLabel: String? = null, val minutesLeft: Int? = null)

/** Text of the capture being transcribed, filled in part by part. */
data class LiveText(val captureId: String, val text: String)

data class LastResult(
    val transcript: String,
    val audioSeconds: Double,
    val modelLoadMs: Long,
    val transcribeMs: Long,
    val chunks: Int,
) {
    val realTimeFactor: Double get() = if (audioSeconds > 0) transcribeMs / 1000.0 / audioSeconds else 0.0
}

object CaptureState {
    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _recording = MutableStateFlow<RecordingInfo?>(null)
    val recording: StateFlow<RecordingInfo?> = _recording.asStateFlow()

    /** Input level 0..1 while recording, for the level meter. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _live = MutableStateFlow<LiveText?>(null)
    val live: StateFlow<LiveText?> = _live.asStateFlow()

    private val _last = MutableStateFlow<LastResult?>(null)
    val last: StateFlow<LastResult?> = _last.asStateFlow()

    /** Capture id currently being processed; its card shows the progress. */
    @Volatile var processingId: String? = null
        private set
    /** Staging file being processed right now; never listed as "waiting". */
    @Volatile var processingFile: String? = null
        private set

    fun set(p: Phase) { _phase.value = p }
    fun recording(r: RecordingInfo?) { _recording.value = r }
    fun processing(file: String?, id: String?) { processingFile = file; processingId = id }
    fun level(v: Float) { _level.value = v }
    fun live(t: LiveText?) { _live.value = t }
    fun result(r: LastResult) { _last.value = r }

    val isRecording: Boolean get() = _recording.value != null
    val isProcessing: Boolean get() = _phase.value.let { it is Phase.LoadingModel || it is Phase.Transcribing || it is Phase.WritingVault }
}
