package org.brain.capture.cleanup

import org.brain.capture.vault.NoteText

/**
 * Pure text side of the voice cleanup (no Android dependencies, unit-tested):
 * numbering the transcript, building the prompt, checking the model's answer, and
 * writing the clean version into the note.
 */
object CleanupText {
    /** Fixed instruction; the numbered transcript follows it. */
    const val INSTRUCTION = """Rewrite this voice note as clean bullet points.
Rules: remove filler words and repeats, fix grammar, put related points together.
Use only what the speaker said. Add no facts, opinions or conclusions.
Keep the language of the transcript.
After each bullet, give the transcript segment numbers it came from, e.g. [2,3].
Answer with the bullet points only, one per line, each starting with "- "."""

    private val SENTENCE_END = Regex("""(?<=[.!?…])\s+""")
    private const val MAX_SEGMENT = 280

    /** Splits a transcript into numbered segments: sentences, with very long ones cut at commas or spaces. */
    fun segments(transcript: String): List<String> =
        transcript.trim().split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }.flatMap(::cutLong)

    private fun cutLong(s: String): List<String> {
        if (s.length <= MAX_SEGMENT) return listOf(s)
        val out = ArrayList<String>()
        var rest = s
        while (rest.length > MAX_SEGMENT) {
            val window = rest.substring(0, MAX_SEGMENT)
            val cut = window.lastIndexOf(", ").takeIf { it > MAX_SEGMENT / 3 }?.plus(1)
                ?: window.lastIndexOf(' ').takeIf { it > 0 } ?: MAX_SEGMENT
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    fun prompt(segments: List<String>): String = buildString {
        appendLine(INSTRUCTION)
        appendLine()
        appendLine("Transcript:")
        segments.forEachIndexed { i, s -> appendLine("[${i + 1}] $s") }
    }

    data class Bullet(val text: String, val refs: List<Int>)

    sealed interface Result {
        data class Ok(val bullets: List<Bullet>) : Result
        data class Rejected(val reason: String) : Result
    }

    private val BULLET = Regex("""^\s*(?:[-*•]|\d+[.)])\s+(.*)$""")
    private val REFS = Regex("""\s*\[\s*(\d+(?:\s*[,–-]\s*\d+)*)\s*]\s*[.]?\s*$""")

    /**
     * Reads the model's bullets. Every bullet must end with segment numbers that exist; otherwise
     * the whole answer is rejected and the note keeps the raw transcript only.
     */
    fun check(answer: String, segmentCount: Int): Result {
        val lines = answer.replace("\r\n", "\n").lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("```") }
        if (lines.isEmpty()) return Result.Rejected("empty answer")
        val bullets = ArrayList<Bullet>()
        for (line in lines) {
            val body = BULLET.find(line)?.groupValues?.get(1)
                ?: return Result.Rejected("not a bullet: \"${line.take(60)}\"")
            val m = REFS.find(body) ?: return Result.Rejected("bullet without segment numbers: \"${body.take(60)}\"")
            val refs = parseRefs(m.groupValues[1])
            if (refs.isEmpty() || refs.any { it !in 1..segmentCount })
                return Result.Rejected("segment number out of range in \"${body.take(60)}\"")
            val text = body.substring(0, m.range.first).trim()
            if (text.isEmpty()) return Result.Rejected("bullet with numbers only")
            bullets += Bullet(text, refs)
        }
        return Result.Ok(bullets)
    }

    private fun parseRefs(s: String): List<Int> = s.split(',').flatMap { part ->
        val r = part.split('-', '–').mapNotNull { it.trim().toIntOrNull() }
        when (r.size) {
            1 -> r
            2 -> if (r[1] >= r[0] && r[1] - r[0] < 200) (r[0]..r[1]).toList() else emptyList()
            else -> emptyList()
        }
    }.distinct().sorted()

    /** Clean bullets with their sources, then the exact words in a collapsed quote. */
    fun cleanBlock(bullets: List<Bullet>, segments: List<String>): String = buildString {
        for (b in bullets) appendLine("- ${b.text} [${b.refs.joinToString(",")}]")
        appendLine()
        appendLine("> [!quote]- Exactly what I said")
        segments.forEachIndexed { i, s -> appendLine("> [${i + 1}] $s") }
    }.trimEnd()

    /**
     * The note with the raw transcript replaced by the clean block, and `cleanup: <model>` added to
     * the frontmatter if it is not there yet. The transcript must stand exactly once on lines of its
     * own (as the app wrote it); inside the "Exactly what I said" quote it never does. Null when the
     * note no longer holds it like that (edited by the user meanwhile, or already cleaned): then the
     * note is left alone.
     *
     * [addition] = the transcript was added to an existing note: the note may already be cleaned.
     */
    fun rewrite(note: String, transcript: String, block: String, model: String, addition: Boolean = false): String? {
        val (front, body) = NoteText.split(note)
        if (front.isEmpty() || transcript.isBlank()) return null
        val marked = front.contains("\ncleanup:")
        if (marked && !addition) return null
        val hits = lineOccurrences(body, transcript)
        if (hits.size != 1) return null
        val at = hits[0]
        val newFront = if (marked) front else front.removeSuffix("---\n") + "cleanup: $model\n---\n"
        return newFront + body.substring(0, at) + block + body.substring(at + transcript.length)
    }

    /** Start positions where [text] fills whole lines: at a line start and followed by a line end. */
    private fun lineOccurrences(body: String, text: String): List<Int> {
        val out = ArrayList<Int>()
        var from = 0
        while (true) {
            val i = body.indexOf(text, from)
            if (i < 0) return out
            val end = i + text.length
            if ((i == 0 || body[i - 1] == '\n') && (end == body.length || body[end] == '\n')) out += i
            from = i + 1
        }
    }
}
