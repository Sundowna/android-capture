package org.brain.capture.pipeline

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.brain.capture.CaptureState
import org.brain.capture.LastResult
import org.brain.capture.Phase
import org.brain.capture.Prefs
import org.brain.capture.audio.OpusEncoder
import org.brain.capture.audio.WavReader
import org.brain.capture.data.Capture
import org.brain.capture.data.CaptureDb
import org.brain.capture.data.CaptureStates
import org.brain.capture.transcribe.Chunker
import org.brain.capture.transcribe.ModelStore
import org.brain.capture.transcribe.WhisperOnnx
import org.brain.capture.vault.NoteBuilder
import org.brain.capture.vault.VaultUnavailableException
import org.brain.capture.vault.VaultWriter
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Recording on disk → transcript → audio + note in the vault.
 * Every finished step is stored in the capture database, so a retry continues where it stopped:
 * no second transcription, no duplicate audio file, no second note or double append.
 * The staging WAV is deleted only after everything is safely in the vault.
 */
class VoicePipeline(private val context: Context) {
    private val prefs = Prefs(context)
    private val dao = CaptureDb.get(context).dao()

    suspend fun run(wav: File) {
        var c = dao.byStaging(wav.path) ?: Capture(
            // A recording from before the database existed: give it a row now.
            id = UUID.randomUUID().toString(),
            createdAt = capturedAt(wav).toInstant().toEpochMilli(),
            state = CaptureStates.RECORDED,
            stagingPath = wav.path,
            appendTo = legacyAppendTarget(wav),
        ).also { dao.upsert(it) }

        CaptureState.processing(wav.path, c.id)
        CaptureState.live(null)
        try {
            if (c.transcript == null) c = transcribeStep(c, wav)
            if (c.isPrivate) { savePrivate(c, wav); return }
            CaptureState.set(Phase.WritingVault)
            val vault = VaultWriter(context, prefs.vaultUri ?: throw VaultUnavailableException("No vault folder selected"))
            if (c.audioVaultName == null) c = audioStep(c, wav, vault)
            c = noteStep(c, vault)
            c = c.copy(state = CaptureStates.SAVED, error = null).also { dao.upsert(it) }
            wav.delete()
            File(wav.path.removeSuffix(".wav") + ".ogg").delete()
            File(wav.parentFile, wav.nameWithoutExtension + ".append").delete()

            val target = c.appendTo?.let { dao.get(it) }
            Undo.record(if (target != null) Undo.Action.Added(c.id) else Undo.Action.Created(c.id))
            CaptureState.live(null)
            CaptureState.set(Phase.Saved(target?.notePath ?: c.notePath ?: "", c.warning, appended = target != null,
                captureId = target?.id ?: c.id))
        } catch (e: VaultUnavailableException) {
            Log.w(TAG, "Vault unavailable", e)
            dao.upsert(c.copy(state = CaptureStates.FAILED, error = e.message))
            CaptureState.set(Phase.Failed("${e.message}. Recording kept.", wav.path))
        } catch (t: Throwable) {
            Log.e(TAG, "Pipeline failed", t)
            dao.upsert(c.copy(state = CaptureStates.FAILED, error = t.message))
            CaptureState.set(Phase.Failed(t.message ?: t.javaClass.simpleName, wav.path))
        }
    }

    /**
     * Private captures never touch the vault: the audio goes to app storage as Opus, the
     * transcript stays in the app's database, and the card shows it from there.
     */
    private suspend fun savePrivate(c0: Capture, wav: File) {
        var c = c0
        if (c.audioVaultName == null) {
            val dir = File(context.filesDir, PRIVATE_DIR).apply { mkdirs() }
            val ogg = File(dir, "${c.id}.ogg")
            val ok = prefs.audioFormat != "wav" && runCatching { OpusEncoder.encode(wav, ogg); true }
                .onFailure { Log.w(TAG, "Opus encoding failed, keeping WAV", it) }.getOrDefault(false)
            val kept = if (ok) ogg else File(dir, "${c.id}.wav").also { wav.copyTo(it, overwrite = true) }
            c = c.copy(audioVaultName = "$PRIVATE_DIR/${kept.name}")
        }
        c = c.copy(state = CaptureStates.SAVED, error = null, lastActivityAt = System.currentTimeMillis()).also { dao.upsert(it) }
        wav.delete()
        File(wav.path.removeSuffix(".wav") + ".ogg").delete()
        Undo.record(if (c.appendTo != null) Undo.Action.Added(c.id) else Undo.Action.Created(c.id))
        CaptureState.live(null)
        CaptureState.set(Phase.Saved("", c.warning, captureId = c.id))
    }

    private suspend fun transcribeStep(c: Capture, wav: File): Capture {
        // One streaming pass for loudness; the audio itself is then read piece by piece, so even
        // hour-long recordings need little memory.
        val profile = WavReader.profile(wav, Chunker.FRAME_SAMPLES)
        val seconds = profile.samples / WhisperOnnx.SAMPLE_RATE.toDouble()
        val muted = profile.mutedFraction
        val warning = when {
            muted > 0.9 -> "No sound was recorded. Is the microphone turned off in Quick Settings?"
            muted > 0.3 -> "The microphone was off for part of this recording."
            else -> null
        }
        // Whisper invents text for silence, so don't transcribe a recording with no sound at all.
        val r = if (muted > 0.9) LastResult("", seconds, 0, 0, 0) else transcribe(wav, profile, seconds, c.id)
        return c.copy(
            state = CaptureStates.TRANSCRIBED,
            transcript = NonSpeech.strip(r.transcript),
            audioSeconds = seconds, modelLoadMs = r.modelLoadMs, transcribeMs = r.transcribeMs, chunks = r.chunks,
            warning = listOfNotNull(c.warning, warning).joinToString(" ").ifEmpty { null },
        ).also { dao.upsert(it) }
    }

    private fun transcribe(wav: File, profile: WavReader.Profile, seconds: Double, captureId: String): LastResult {
        val store = ModelStore(context)
        if (!store.isInstalled()) error("Speech model not installed")
        CaptureState.set(Phase.LoadingModel)
        // Background priority (Whisper's worker threads inherit it): the screen stays smooth.
        return org.brain.capture.cleanup.GemmaHolder.background { transcribeWith(store, wav, profile, seconds, captureId) }
    }

    private fun transcribeWith(store: ModelStore, wav: File, profile: WavReader.Profile, seconds: Double, captureId: String): LastResult {
        val t0 = SystemClock.elapsedRealtime()
        WhisperOnnx(store.dir).use { whisper ->
            val loadMs = SystemClock.elapsedRealtime() - t0
            val chunks = Chunker.plan(profile.energy, profile.samples)
            val lang = prefs.language.takeIf { it != "auto" }
            val t1 = SystemClock.elapsedRealtime()
            val done = ArrayList<String>()
            val parts = chunks.mapIndexed { i, range ->
                // Time left = average time of the finished parts × parts still to do (none before the first).
                val elapsed = SystemClock.elapsedRealtime() - t1
                val eta = if (i == 0) null else (elapsed / i * (chunks.size - i) / 1000).toInt()
                CaptureState.set(Phase.Transcribing(i, chunks.size, eta))
                val chunk = WavReader.readRange(wav, range.first, range.last + 1)
                // Pieces without any speech-level sound are skipped (Whisper invents text for silence).
                val text = if (AudioCheck.loudestFrame(chunk) < SPEECH_LEVEL) "" else NonSpeech.strip(whisper.transcribe(chunk, lang))
                if (text.isNotBlank()) done += text
                CaptureState.live(org.brain.capture.LiveText(captureId, done.joinToString(" ")))
                text
            }
            val text = parts.filter { it.isNotBlank() }.joinToString(" ")
            val txMs = SystemClock.elapsedRealtime() - t1
            val r = LastResult(text, seconds, loadMs, txMs, chunks.size)
            CaptureState.result(r)
            Log.i(TAG, "Benchmark: audio=%.1fs load=%dms transcribe=%dms chunks=%d rtf=%.2f"
                .format(seconds, loadMs, txMs, chunks.size, txMs / 1000.0 / seconds))
            return r
        }
    }

    /** Puts the recording into the vault as Opus (.ogg); falls back to WAV if encoding fails. */
    private suspend fun audioStep(c: Capture, wav: File, vault: VaultWriter): Capture {
        val inbox = prefs.inboxFolder.trim('/')
        val at = capturedAt(wav)
        vault.cleanupTemp("$inbox/Attachments")
        val ogg = File(wav.path.removeSuffix(".wav") + ".ogg")
        val encoded = prefs.audioFormat != "wav" && runCatching { OpusEncoder.encode(wav, ogg); true }
            .onFailure { Log.w(TAG, "Opus encoding failed, keeping WAV", it) }.getOrDefault(false)
        val source = if (encoded) ogg else wav
        val name = vault.write("$inbox/Attachments", NoteBuilder.baseName(at, "Voice", null), source.extension) { out ->
            source.inputStream().use { it.copyTo(out, 1 shl 16) }
        }
        Log.i(TAG, "Audio saved: $name (${source.length() / 1024} KB, WAV was ${wav.length() / 1024} KB)")
        return c.copy(audioVaultName = name).also { dao.upsert(it) }
    }

    private suspend fun noteStep(c: Capture, vault: VaultWriter): Capture {
        val inbox = prefs.inboxFolder.trim('/')
        val at = OffsetDateTime.ofInstant(Instant.ofEpochMilli(c.createdAt), ZoneId.systemDefault())
        val transcript = c.transcript.orEmpty()

        val target = c.appendTo?.let { dao.get(it) }
        if (target != null && !c.appended) {
            val path = Notes.locate(context, vault, target)
            if (path != null) {
                // If an earlier attempt already appended (crash before the database update), don't do it twice.
                val already = vault.read(path)?.contains("![[${c.audioVaultName}]]") == true
                if (already || vault.append(path, NoteBuilder.appendBlock(at, transcript, c.audioVaultName, c.warning))) {
                    dao.touch(target.id, System.currentTimeMillis())
                    return c.copy(appended = true).also { dao.upsert(it) }
                }
            }
            // The note to add to is gone: keep the addition as its own note, linked to the original.
        }
        if (c.appended) return c

        if (c.notePath == null) {
            vault.cleanupTemp("$inbox/Voice")
            // A note may already exist from an attempt that crashed before the database update.
            val existing = vault.findNoteById(c.id, maxFiles = 500, startFolder = "$inbox/Voice")
            val path = existing ?: run {
                val body = if (c.appendTo == null) transcript
                else "Addition to [[${target?.notePath?.substringAfterLast('/')?.removeSuffix(".md") ?: "earlier note"}]]\n\n$transcript"
                val note = NoteBuilder.voiceNote(
                    captureId = c.id,
                    at = at,
                    durationSec = c.audioSeconds,
                    audioFile = c.audioVaultName ?: "",
                    transcript = body,
                    model = "whisper-small-int8 (onnx)",
                    language = prefs.language,
                    warning = c.warning,
                    type = c.type,
                )
                val name = vault.write("$inbox/Voice", NoteBuilder.baseName(at, "Voice", transcript), "md") {
                    it.write(note.toByteArray())
                }
                "$inbox/Voice/$name"
            }
            // An orphaned addition becomes a card of its own.
            return c.copy(notePath = path, appendTo = null, lastActivityAt = System.currentTimeMillis())
                .also { dao.upsert(it) }
        }
        return c
    }

    /** Append target written by the previous app version next to the recording. */
    private suspend fun legacyAppendTarget(wav: File): String? {
        val f = File(wav.parentFile, wav.nameWithoutExtension + ".append")
        val path = f.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null } ?: return null
        return dao.byNote(path)?.id
    }

    /** The staging file name holds the recording start time (yyyy-MM-dd_HHmmss). */
    private fun capturedAt(wav: File) = runCatching {
        LocalDateTime.parse(wav.nameWithoutExtension, DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss", Locale.ROOT))
            .atZone(ZoneId.systemDefault()).toOffsetDateTime()
    }.getOrElse { OffsetDateTime.now() }

    companion object {
        private const val TAG = "VoicePipeline"
        /** Folder in app storage for private recordings. */
        const val PRIVATE_DIR = "private"
        /** Loudest 100 ms must reach about -48 dBFS; quiet speech is well above, room noise below. */
        private const val SPEECH_LEVEL = 0.004
    }
}

/** Whisper writes tags such as "[BLANK_AUDIO]", "[ Silence ]" or "(heartbeat)" when nobody speaks. */
object NonSpeech {
    private val SQUARE = Regex("""\[[^\]]*\]""")
    private val ONLY_TAGS = Regex("""^(\s*(\([^)]*\)|\*[^*]*\*|♪+)\s*)*$""")
    private val SPACES = Regex("""\s+""")

    fun strip(text: String): String {
        val t = text.replace(SQUARE, " ").replace(SPACES, " ").trim()
        return if (ONLY_TAGS.matches(t)) "" else t
    }
}

object AudioCheck {
    /** RMS of the loudest 100 ms frame. */
    fun loudestFrame(samples: FloatArray): Double {
        val frame = WhisperOnnx.SAMPLE_RATE / 10
        var best = 0.0
        var f = 0
        while (f + frame <= samples.size) {
            var sum = 0.0
            for (i in f until f + frame) sum += samples[i] * samples[i]
            best = maxOf(best, kotlin.math.sqrt(sum / frame))
            f += frame
        }
        return best
    }

    /** Share of 100 ms frames that are exact digital silence, which is what a muted microphone delivers. */
    fun mutedFraction(samples: FloatArray): Double {
        val frame = WhisperOnnx.SAMPLE_RATE / 10
        val frames = samples.size / frame
        if (frames == 0) return 1.0
        var muted = 0
        for (f in 0 until frames) {
            var allZero = true
            for (i in f * frame until (f + 1) * frame) if (samples[i] != 0f) { allZero = false; break }
            if (allZero) muted++
        }
        return muted / frames.toDouble()
    }
}
