package org.brain.capture.vault

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object NoteBuilder {
    // Colons are not allowed in Obsidian file names (or on Windows), so the time uses dots.
    private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss", Locale.ROOT)

    /** e.g. "2026-11-10 08.53.12 Voice – Remember to call the dentist" */
    fun baseName(at: OffsetDateTime, kind: String, text: String?): String {
        val words = text.orEmpty().split(Regex("\\s+")).filter { it.isNotBlank() }.take(6).joinToString(" ")
        val title = safe(words).take(50).trim()
        val stamp = "${at.format(FILE_STAMP)} $kind"
        return if (title.isEmpty()) stamp else "$stamp – $title"
    }

    fun voiceNote(
        captureId: String,
        at: OffsetDateTime,
        durationSec: Double,
        audioFile: String,
        transcript: String?,
        model: String,
        language: String,
        warning: String? = null,
    ): String = buildString {
        appendLine("---")
        appendLine("type: capture")
        appendLine("source: voice")
        appendLine("captured: ${at.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}")
        appendLine("duration: ${formatDuration(durationSec)}")
        appendLine("audio: \"[[${audioFile}]]\"")
        appendLine("transcription: ${if (transcript == null) "none" else model}")
        appendLine("language: $language")
        appendLine("capture_id: $captureId")
        appendLine("status: inbox")
        appendLine("---")
        appendLine()
        warning?.let { appendLine("> [!warning] $it"); appendLine() }
        appendLine(transcript?.ifBlank { "_(no speech recognised)_" } ?: "_(transcription pending)_")
        appendLine()
        appendLine("![[${audioFile}]]")
    }

    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

    /** Text added to the end of an existing note when the user records more for it. */
    fun appendBlock(at: OffsetDateTime, transcript: String, audioFile: String, warning: String?): String = buildString {
        appendLine()
        appendLine("**Added ${at.format(TIME)}**")
        appendLine()
        warning?.let { appendLine("> [!warning] $it"); appendLine() }
        appendLine(transcript.ifBlank { "_(no speech recognised)_" })
        appendLine()
        appendLine("![[${audioFile}]]")
    }

    private fun formatDuration(sec: Double): String {
        val s = sec.toLong()
        return "%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    }

    /** Removes characters that are invalid in file names or break Obsidian links. */
    private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|#^\\[\\]]"), "").replace(Regex("[.]+$"), "")
}
