package org.brain.capture.pipeline

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.brain.capture.CaptureState
import org.brain.capture.LastResult
import org.brain.capture.Phase
import org.brain.capture.Prefs
import org.brain.capture.audio.WavReader
import org.brain.capture.transcribe.Chunker
import org.brain.capture.transcribe.ModelStore
import org.brain.capture.transcribe.WhisperOnnx
import org.brain.capture.vault.NoteBuilder
import org.brain.capture.vault.VaultUnavailableException
import org.brain.capture.vault.VaultWriter
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Recording on disk → transcript → audio + note in the vault.
 * The staging WAV is deleted only after both files are safely in the vault, so a failure at
 * any step leaves the recording available for Retry.
 */
class VoicePipeline(private val context: Context) {
    private val prefs = Prefs(context)

    suspend fun run(wav: File) {
        try {
            val samples = WavReader.readFloats(wav)
            val seconds = samples.size / WhisperOnnx.SAMPLE_RATE.toDouble()
            val transcript = transcribe(samples, seconds)
            CaptureState.set(Phase.WritingVault)
            val path = writeToVault(wav, seconds, transcript)
            wav.delete()
            CaptureState.set(Phase.Saved(path))
        } catch (e: VaultUnavailableException) {
            Log.w(TAG, "Vault unavailable", e)
            CaptureState.set(Phase.Failed("${e.message}. Recording kept.", wav.path))
        } catch (t: Throwable) {
            Log.e(TAG, "Pipeline failed", t)
            CaptureState.set(Phase.Failed(t.message ?: t.javaClass.simpleName, wav.path))
        }
    }

    private fun transcribe(samples: FloatArray, seconds: Double): String {
        val store = ModelStore(context)
        if (!store.isInstalled()) error("Whisper model not imported")
        CaptureState.set(Phase.LoadingModel)
        val t0 = SystemClock.elapsedRealtime()
        WhisperOnnx(store.dir).use { whisper ->
            val loadMs = SystemClock.elapsedRealtime() - t0
            val chunks = Chunker.split(samples)
            val lang = prefs.language.takeIf { it != "auto" }
            val t1 = SystemClock.elapsedRealtime()
            val parts = chunks.mapIndexed { i, chunk ->
                CaptureState.set(Phase.Transcribing(i, chunks.size))
                whisper.transcribe(chunk, lang)
            }
            val text = parts.filter { it.isNotBlank() }.joinToString(" ")
            val txMs = SystemClock.elapsedRealtime() - t1
            CaptureState.result(LastResult(text, seconds, loadMs, txMs, chunks.size))
            Log.i(TAG, "Benchmark: audio=%.1fs load=%dms transcribe=%dms chunks=%d rtf=%.2f"
                .format(seconds, loadMs, txMs, chunks.size, txMs / 1000.0 / seconds))
            return text
        }
    }

    private fun writeToVault(wav: File, seconds: Double, transcript: String): String {
        val tree = prefs.vaultUri ?: throw VaultUnavailableException("No vault folder selected")
        val vault = VaultWriter(context, tree)
        val inbox = prefs.inboxFolder.trim('/')
        val at = capturedAt(wav)
        val base = NoteBuilder.baseName(at, "Voice", transcript)

        vault.cleanupTemp("$inbox/Attachments")
        vault.cleanupTemp("$inbox/Voice")
        // Audio first, so a note never links to a missing file.
        val audioName = vault.write("$inbox/Attachments", NoteBuilder.baseName(at, "Voice", null), "wav") { out ->
            wav.inputStream().use { it.copyTo(out, 1 shl 16) }
        }
        val note = NoteBuilder.voiceNote(
            captureId = UUID.randomUUID().toString(),
            at = at,
            durationSec = seconds,
            audioFile = audioName,
            transcript = transcript,
            model = "whisper-small-int8 (onnx)",
            language = prefs.language,
        )
        val noteName = vault.write("$inbox/Voice", base, "md") { it.write(note.toByteArray()) }
        return "$inbox/Voice/$noteName"
    }

    /** The staging file name holds the recording start time (yyyy-MM-dd_HHmmss). */
    private fun capturedAt(wav: File) = runCatching {
        LocalDateTime.parse(wav.nameWithoutExtension, DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss", Locale.ROOT))
            .atZone(ZoneId.systemDefault()).toOffsetDateTime()
    }.getOrElse { java.time.OffsetDateTime.now() }

    companion object { private const val TAG = "VoicePipeline" }
}
