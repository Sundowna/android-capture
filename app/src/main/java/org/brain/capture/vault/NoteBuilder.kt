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
        appendLine("captured: ${at.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}")
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

    data class SharedFile(val vaultName: String, val mime: String?, val size: Long, val sha256: String, val pages: Int = -1)

    /** The app something was shared from: readable [name] ("Files") and [pkg] (package name). */
    data class Source(val name: String?, val pkg: String?)

    private fun sourceFields(s: Source?) = listOfNotNull(s?.name?.let { "source_app" to it }, s?.pkg?.let { "source_package" to it })

    private val READABLE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)

    /** "Shared from Files on 10 Nov 2026, 18:40 — PDF, 12 pages" */
    fun sharedLine(at: OffsetDateTime, source: Source?, files: List<SharedFile>): String {
        val from = source?.name?.let { "from $it " }.orEmpty()
        return "Shared ${from}on ${at.format(READABLE)} — ${describe(files)}"
    }

    /** "1 image", "3 images", "PDF, 12 pages", "2 PDFs", "3 files". */
    fun describe(files: List<SharedFile>): String {
        val n = files.size
        return when {
            files.all { it.mime?.startsWith("image/") == true } -> if (n == 1) "1 image" else "$n images"
            files.all { it.mime == "application/pdf" } ->
                if (n == 1) "PDF" + (files[0].pages.takeIf { it > 0 }?.let { ", $it page${if (it == 1) "" else "s"}" } ?: "")
                else "$n PDFs"
            else -> if (n == 1) "1 file" else "$n files"
        }
    }

    /** Attachment name for a shared file: its own name if it has one, else "<time> Shared". */
    fun fileBaseName(at: OffsetDateTime, original: String?): String =
        safe(original.orEmpty()).take(80).trim().ifEmpty { "${at.format(FILE_STAMP)} Shared" }

    fun linkNote(captureId: String, at: OffsetDateTime, url: String, title: String?, text: String?, source: Source?): String =
        shareNote(captureId, at, "link", listOfNotNull("url" to url, title?.let { "title" to it }) + sourceFields(source)) {
            appendLine("# ${title ?: url}")
            appendLine()
            appendLine("<$url>")
            text?.let { appendLine(); it.lines().forEach { l -> appendLine("> $l") } }
        }

    fun textNote(captureId: String, at: OffsetDateTime, text: String, source: Source?): String =
        shareNote(captureId, at, "text", sourceFields(source)) { appendLine(text) }

    fun fileNote(captureId: String, at: OffsetDateTime, files: List<SharedFile>, text: String?, source: Source?): String =
        shareNote(captureId, at, "file", buildList {
            add("files" to files.joinToString(", ") { "[[${it.vaultName}]]" })
            if (files.size == 1) { add("mime" to (files[0].mime ?: "")); add("size" to files[0].size.toString()); add("sha256" to files[0].sha256) }
            addAll(sourceFields(source))
        }) {
            // A readable line, so the note makes sense on its own and not only as embeds.
            appendLine(sharedLine(at, source, files))
            appendLine()
            text?.let { appendLine(it); appendLine() }
            for (f in files) {
                // PDFs and images show inline in Obsidian; other files become a link.
                val inline = f.mime == "application/pdf" || f.mime?.startsWith("image/") == true
                appendLine(if (inline) "![[${f.vaultName}]]" else "[[${f.vaultName}]]")
                appendLine()
            }
        }

    private fun shareNote(captureId: String, at: OffsetDateTime, source: String, fields: List<Pair<String, String>>,
                          body: StringBuilder.() -> Unit): String = buildString {
        appendLine("---")
        appendLine("type: capture")
        appendLine("source: $source")
        appendLine("captured: ${at.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}")
        for ((k, v) in fields) appendLine("$k: ${yaml(v)}")
        appendLine("capture_id: $captureId")
        appendLine("status: inbox")
        appendLine("---")
        appendLine()
        body()
    }

    /** YAML-safe value: quoted with escapes when it contains anything special. */
    internal fun yaml(v: String): String =
        if (v.isNotEmpty() && v.all { it.isLetterOrDigit() || it in "-_./" }) v
        else "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""

    /** Text added to the end of an existing note when the user records more for it. */
    fun appendBlock(at: OffsetDateTime, transcript: String, audioFile: String?, warning: String?, typed: Boolean = false): String = buildString {
        appendLine()
        appendLine("**Added ${at.format(TIME)}${if (typed) " (typed)" else ""}**")
        appendLine()
        warning?.let { appendLine("> [!warning] $it"); appendLine() }
        appendLine(transcript.ifBlank { "_(no speech recognised)_" })
        if (audioFile != null) {
            appendLine()
            appendLine("![[${audioFile}]]")
        }
    }

    private fun formatDuration(sec: Double): String {
        val s = sec.toLong()
        return "%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    }

    /** Removes characters that are invalid in file names or break Obsidian links. */
    private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|#^\\[\\]]"), "").replace(Regex("[.]+$"), "")
}
