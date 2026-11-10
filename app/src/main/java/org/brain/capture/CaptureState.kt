package org.brain.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the capture pipeline is doing right now. Shown in the app, the notification and the logs. */
sealed interface Phase {
    data object Idle : Phase
    data class Recording(val startedAtMs: Long) : Phase
    data object SavingAudio : Phase
    data object LoadingModel : Phase
    /** [done] of [total] chunks finished. Progress is counted in real chunks, never estimated. */
    data class Transcribing(val done: Int, val total: Int) : Phase
    data object WritingVault : Phase
    data class Saved(val notePath: String) : Phase
    data class Failed(val message: String, val wavPath: String?) : Phase
}

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

    /** Input level 0..1 while recording, for the level meter. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _last = MutableStateFlow<LastResult?>(null)
    val last: StateFlow<LastResult?> = _last.asStateFlow()

    fun set(p: Phase) { _phase.value = p }
    fun level(v: Float) { _level.value = v }
    fun result(r: LastResult) { _last.value = r }

    val isRecording: Boolean get() = _phase.value is Phase.Recording
    val isBusy: Boolean get() = when (_phase.value) {
        Phase.Idle, is Phase.Saved, is Phase.Failed -> false
        else -> true
    }
}
