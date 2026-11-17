package org.brain.capture.cleanup

import org.brain.capture.vault.NoteText

/**
 * Pure text side of the voice cleanup (no Android dependencies, unit-tested):
 * numbering the transcript, building the prompt, checking the model's answer, and
 * writing the clean version into the note.
 */
object CleanupText {
    /**
     * The rules every instruction ends with, whatever the user wrote above them: they make each line
     * traceable (segment numbers) and give the answer the shape [check] reads. Not editable.
     */
    private const val BULLET_RULES = """After each bullet, give the transcript segment numbers it came from, e.g. [2,3].
First line: "Title: " and a short title of at most 8 words, made from the speaker's own words, in the language of the transcript.
Then the bullet points only, one per line, each starting with "- "."""

    private fun checklistRules(today: String) = """If the speaker gives a day or date for a task, add " (due YYYY-MM-DD)". Today is $today.
After each task, give the transcript segment numbers it came from, e.g. [2,3].
First line: "Title: " and a short title of at most 8 words, made from the speaker's own words, in the language of the transcript.
Then the tasks only, one per line, each starting with "- [ ] "."""

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

    /** The built-in Idea or Task instruction; kept for callers that only know the type. */
    fun prompt(segments: List<String>, type: String = "idea", today: String = ""): String =
        prompt(segments, if (type == "task") org.brain.capture.types.Profiles.TASK_INSTRUCTION else org.brain.capture.types.Profiles.IDEA_INSTRUCTION,
            checklist = type == "task", today = today)

    /** [instruction] is the type's own (editable) text; the fixed rules for its format follow it. */
    fun prompt(segments: List<String>, instruction: String, checklist: Boolean, today: String): String = buildString {
        appendLine(instruction.trim())
        appendLine(if (checklist) checklistRules(today) else BULLET_RULES)
        appendLine()
        appendLine("Transcript:")
        segments.forEachIndexed { i, s -> appendLine("[${i + 1}] $s") }
    }

    data class Bullet(val text: String, val refs: List<Int>, val task: Boolean = false)

    sealed interface Result {
        data class Ok(val bullets: List<Bullet>, val title: String? = null) : Result
        data class Rejected(val reason: String) : Result
    }

    private val BULLET = Regex("""^\s*(?:[-*•]|\d+[.)])\s+(.*)$""")
    private val TASK = Regex("""^\[[ xX]?]\s+(.*)$""")
    private val TITLE = Regex("""^(?:#+\s*)?\**(?:title|titel)\**\s*:\s*\**(.+?)\**$""", RegexOption.IGNORE_CASE)
    private val ADDED = Regex("""\*\*Added (\d{1,2}:\d{2})(?::\d{2})?([^*]*)\*\*""")
    private val REFS = Regex("""\s*\[\s*(\d+(?:\s*[,–-]\s*\d+)*)\s*]\s*[.]?\s*$""")

    /**
     * Reads the model's answer: an optional "Title:" first line (no sources needed, it only
     * names the note), then bullets or "- [ ]" tasks. Every bullet must end with segment numbers
     * that exist; otherwise the whole answer is rejected and the note keeps the raw transcript only.
     */
    fun check(answer: String, segmentCount: Int): Result {
        var lines = answer.replace("\r\n", "\n").lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("```") }
        if (lines.isEmpty()) return Result.Rejected("empty answer")
        val title = TITLE.find(lines[0])?.groupValues?.get(1)?.trim()?.trim('"')?.take(80)?.ifEmpty { null }
        if (title != null) lines = lines.drop(1)
        if (lines.isEmpty()) return Result.Rejected("title only")
        val bullets = ArrayList<Bullet>()
        for (line in lines) {
            var body = BULLET.find(line)?.groupValues?.get(1)
                ?: return Result.Rejected("not a bullet: \"${line.take(60)}\"")
            val task = TASK.find(body)?.groupValues?.get(1)
            if (task != null) body = task
            val m = REFS.find(body) ?: return Result.Rejected("bullet without segment numbers: \"${body.take(60)}\"")
            val refs = parseRefs(m.groupValues[1])
            if (refs.isEmpty() || refs.any { it !in 1..segmentCount })
                return Result.Rejected("segment number out of range in \"${body.take(60)}\"")
            val text = body.substring(0, m.range.first).trim()
            if (text.isEmpty()) return Result.Rejected("bullet with numbers only")
            bullets += Bullet(text, refs, task = task != null)
        }
        return Result.Ok(bullets, title)
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
        append(bulletLines(bullets))
        appendLine()
        appendLine()
        appendLine("> [!quote]- Exactly what I said")
        segments.forEachIndexed { i, s -> appendLine("> [${i + 1}] $s") }
    }.trimEnd()

    /** The bullets alone, one per line, each ending with its sources: "- text [1,2]" or "- [ ] text [3]". */
    fun bulletLines(bullets: List<Bullet>): String =
        bullets.joinToString("\n") { "- ${if (it.task) "[ ] " else ""}${it.text} [${it.refs.joinToString(",")}]" }

    /** What the app keeps for the capture screen and cards: "# Title" (if any), then the bullets. */
    fun stored(title: String?, bullets: List<Bullet>): String =
        (title?.let { "# $it\n" } ?: "") + bulletLines(bullets)

    /**
     * Note text for a card: the "Exactly what I said" quotes are folded away and the segment
     * numbers after bullets are hidden. The note itself keeps both.
     */
    fun forDisplay(body: String): String {
        val out = ArrayList<String>()
        var inQuote = false
        for (line in body.lines()) {
            if (line.startsWith("> [!quote]- Exactly what I said")) { inQuote = true; continue }
            if (inQuote && line.startsWith(">")) continue
            inQuote = false
            // "**Added 19:43:16**" (an addition's heading) reads as "Added 19:43" on a card.
            val added = ADDED.matchEntire(line.trim())
            if (added != null) { out += "Added " + added.groupValues[1] + added.groupValues[2]; continue }
            val t = line.trimStart()
            out += when {
                t.startsWith("- [ ] ") -> "☐ " + t.removePrefix("- [ ] ").replace(REFS, "")
                t.startsWith("- [x] ") -> "☑ " + t.removePrefix("- [x] ").replace(REFS, "")
                t.startsWith("- ") -> "• " + t.removePrefix("- ").replace(REFS, "")
                else -> line
            }
        }
        return out.joinToString("\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    /** [stored] text as plain lines for a card: the title, then "• " bullets or "☐ " tasks. */
    fun storedForDisplay(s: String): String = parseStored(s).joinToString("\n") {
        when { it.title -> it.text; it.task -> "☐ ${it.text}"; else -> "• ${it.text}" }
    }

    /** A line of [stored] text, ready to show: its sources, and whether it is a title or task. */
    data class Line(val text: String, val refs: List<Int>, val title: Boolean = false, val task: Boolean = false)

    fun parseStored(s: String): List<Line> = s.lines().filter { it.isNotBlank() }.map { l ->
        when {
            l.startsWith("# ") -> Line(l.removePrefix("# ").trim(), emptyList(), title = true)
            else -> {
                var body = l.removePrefix("- ")
                val task = body.startsWith("[ ] ").also { if (it) body = body.removePrefix("[ ] ") }
                val m = REFS.find(body)
                Line(if (m != null) body.substring(0, m.range.first).trim() else body.trim(),
                    m?.let { parseRefs(it.groupValues[1]) } ?: emptyList(), task = task)
            }
        }
    }

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
