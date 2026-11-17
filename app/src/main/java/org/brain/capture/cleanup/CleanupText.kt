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
Then the bullet points only, one per line, each starting with "- ".
If the transcript holds nothing to list, write only the title line. Never make up content."""

    /** Paragraphs and bullets mixed; every paragraph is traceable like a bullet. */
    private const val MIXED_RULES = """End every paragraph and every bullet with the transcript segment numbers it came from, e.g. [2,3].
First line: "Title: " and a short title of at most 8 words, made from the speaker's own words, in the language of the transcript.
Then the text only. Sentences that belong together form a paragraph: one line of its own, with no "- " in front.
Use "- " only for the items of a real list, each on its own line.
Layout (placeholders, not content):
Title: <title>
<paragraph> [1,2]
<what the list is for>: [3]
- <item> [3]
- <item> [3]
<paragraph> [4]
If the transcript holds nothing to keep, write only the title line. Never make up content."""

    private fun checklistRules(today: String) = """If the speaker gives a day or date for a task, add " (due YYYY-MM-DD)". Today is $today.
After each task, give the transcript segment numbers it came from, e.g. [2,3].
First line: "Title: " and a short title of at most 8 words, made from the speaker's own words, in the language of the transcript.
Then the tasks only, one per line, each starting with "- [ ] ".
If the transcript holds nothing to list, write only the title line. Never make up content."""

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
    fun prompt(segments: List<String>, instruction: String, checklist: Boolean, today: String, mixed: Boolean = false): String = buildString {
        appendLine(instruction.trim())
        appendLine(when { checklist -> checklistRules(today); mixed -> MIXED_RULES; else -> BULLET_RULES })
        appendLine()
        appendLine("Transcript:")
        segments.forEachIndexed { i, s -> appendLine("[${i + 1}] $s") }
    }

    /** One line of the clean version: a bullet, a task, or (in the mixed format) a paragraph. */
    data class Bullet(val text: String, val refs: List<Int>, val task: Boolean = false, val para: Boolean = false)

    sealed interface Result {
        data class Ok(val bullets: List<Bullet>, val title: String? = null) : Result
        /** [retry] = a format slip worth one more try; false when the speaker gave nothing to list. */
        data class Rejected(val reason: String, val retry: Boolean = true) : Result
    }

    private val LETTERS = Regex("""\p{L}+""")
    /** Words the format itself adds ("Fix: …", "(due 2026-11-20)"), not the speaker. */
    private val FORMAT_WORDS = setOf("fix", "feature", "title", "titel", "due", "works", "question", "should", "later")
    /** A line counts as said when at least this share of its words was in the transcript. */
    private const val MIN_GROUNDED = 0.5

    private fun stem(w: String) = w.lowercase().take(4)
    private fun words(s: String) = LETTERS.findAll(s).map { it.value }.filter { it.length >= 3 && it.lowercase() !in FORMAT_WORDS }.toList()

    /**
     * Share of [line]'s words (by their first four letters, so "tapping" matches "tap") that the
     * speaker said somewhere in the transcript. A small model sometimes invents whole lines, with
     * valid segment numbers, when there is little to clean ("Okay, let's see." became two features).
     */
    fun grounded(line: String, said: Set<String>): Double {
        val w = words(line)
        return if (w.isEmpty()) 1.0 else w.count { stem(it) in said }.toDouble() / w.size
    }

    /**
     * [check], then drops every line (and the title) whose words were not said. Nothing left: rejected,
     * and the note keeps the exact words. In tests, 261 real cleaned lines scored 0.80 or more;
     * invented ones scored 0.
     */
    fun check(answer: String, segments: List<String>, mixed: Boolean = false): Result {
        val r = check(answer, segments.size, mixed)
        if (r !is Result.Ok) return r
        val said = segments.flatMap(::words).map(::stem).toSet()
        val kept = r.bullets.filter { grounded(it.text, said) >= MIN_GROUNDED }
        if (kept.isEmpty()) return Result.Rejected("no line matches what was said", retry = false)
        return Result.Ok(kept, r.title?.takeIf { grounded(it, said) >= MIN_GROUNDED })
    }

    private val BULLET = Regex("""^\s*(?:[-*•]|\d+[.)])\s+(.*)$""")
    private val TASK = Regex("""^\[[ xX]?]\s+(.*)$""")
    private val TITLE = Regex("""^(?:#+\s*)?\**(?:title|titel)\**\s*:\s*\**(.+?)\**$""", RegexOption.IGNORE_CASE)
    private val ADDED = Regex("""\*\*Added (\d{1,2}:\d{2})(?::\d{2})?([^*]*)\*\*""")
    /** The sources at the end of a line: "[2,3]", "[1-3]", or several groups such as "[3] [4]". */
    private val REFS = Regex("""\s*((?:\[\s*\d+(?:\s*[,–-]\s*\d+)*\s*]\s*)+)[.]?\s*$""")
    private val LEAD_REFS = Regex("""^(?:[-*•]\s+)?((?:\[\s*\d+(?:\s*[,–-]\s*\d+)*\s*]\s*)+)(\S.*)$""")
    private val TODO_LINE = Regex("""^(?:Fix|Feature)\s*:""", RegexOption.IGNORE_CASE)
    private val ONLY_REFS =Regex("""^(?:\[\s*\d+(?:\s*[,–-]\s*\d+)*\s*]\s*)+[.]?$""")

    /**
     * Reads the model's answer: an optional "Title:" first line (no sources needed, it only
     * names the note), then bullets or "- [ ]" tasks. Every bullet must end with segment numbers
     * that exist; otherwise the whole answer is rejected and the note keeps the raw transcript only.
     */
    fun check(answer: String, segmentCount: Int, mixed: Boolean = false): Result {
        var lines = answer.replace("\r\n", "\n").lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("```") }
        // A line holding only sources belongs to the line above it (the model sometimes breaks the line there).
        lines = lines.fold(ArrayList<String>()) { acc, l ->
            if (acc.isNotEmpty() && ONLY_REFS.matches(l) && !REFS.containsMatchIn(acc.last())) acc[acc.size - 1] = acc.last() + " " + l
            else acc += l
            acc
        }
        if (lines.isEmpty()) return Result.Rejected("empty answer")
        val title = TITLE.find(lines[0])?.groupValues?.get(1)?.trim()?.trim('"')?.take(80)?.ifEmpty { null }
        if (title != null) lines = lines.drop(1)
        if (lines.isEmpty()) return Result.Rejected("title only: nothing to list", retry = false)
        val bullets = ArrayList<Bullet>()
        for ((n, line0) in lines.withIndex()) {
            // A lead-in such as "Three things for the trip:" right before a list: it takes the list's sources.
            var line = if (line0.endsWith(":") && !REFS.containsMatchIn(line0))
                lines.getOrNull(n + 1)?.let { REFS.find(it) }?.let { line0 + " " + it.value.trim() } ?: line0 else line0
            // Sources written in front ("- [2,3] Fix: …", "[6] Question: …") count as if they stood at the end.
            LEAD_REFS.matchEntire(line)?.let { m ->
                val rest = m.groupValues[2]
                line = "- " + if (REFS.containsMatchIn(rest)) rest else rest + " " + m.groupValues[1].trim()
            }
            val bullet = BULLET.find(line)?.groupValues?.get(1)
            // The mixed format: a line that is not a bullet is a paragraph, held to the same rules.
            if (bullet == null && !mixed) return Result.Rejected("not a bullet: \"${line.take(60)}\"")
            var body = bullet ?: line
            val task = TASK.find(body)?.groupValues?.get(1)
            if (task != null) body = task
            val m = REFS.find(body) ?: return Result.Rejected("bullet without segment numbers: \"${body.take(60)}\"")
            val refs = parseRefs(m.groupValues[1])
            if (refs.isEmpty() || refs.any { it !in 1..segmentCount })
                return Result.Rejected("segment number out of range in \"${body.take(60)}\"")
            val text = body.substring(0, m.range.first).trim()
            if (text.isEmpty()) return Result.Rejected("bullet with numbers only")
            // Fix and Feature lines are to-dos: the app gives them their box (the model mixes boxes up with sources).
            val todo = task != null || (bullet != null && TODO_LINE.containsMatchIn(text))
            bullets += Bullet(text, refs, task = todo, para = bullet == null)
        }
        return Result.Ok(bullets, title)
    }

    private fun parseRefs(s: String): List<Int> = s.replace('[', ',').replace(']', ',').split(',').filter { it.isNotBlank() }.flatMap { part ->
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

    /** The bullets of a stored clean version, as they were written into the note. */
    fun bulletsFromStored(s: String): List<Bullet> = parseStored(s).filter { !it.title }.map { Bullet(it.text, it.refs, it.task, it.para) }

    /**
     * The note with one clean block swapped for another (cleaning again with a different type).
     * Null unless [old] stands in the note exactly once, as the app wrote it: an edited note is left alone.
     */
    fun replaceBlock(note: String, old: String, new: String): String? {
        val i = note.indexOf(old)
        if (old.isBlank() || i < 0 || note.indexOf(old, i + 1) >= 0) return null
        return note.substring(0, i) + new + note.substring(i + old.length)
    }

    /** Sets `capture_type: <type>` in the frontmatter; the default type (Idea) has no line. */
    fun withCaptureType(note: String, type: String): String {
        val (front, body) = NoteText.split(note)
        if (front.isEmpty()) return note
        val lines = front.removeSuffix("---\n").lines().filter { it.isNotEmpty() && !it.startsWith("capture_type:") }.toMutableList()
        if (type != "idea") {
            val at = lines.indexOfFirst { it.startsWith("source:") }.let { if (it < 0) lines.size else it + 1 }
            lines.add(at, "capture_type: $type")
        }
        return lines.joinToString("\n") + "\n---\n" + body
    }

    /**
     * The lines alone, each ending with its sources: "- text [1,2]", "- [ ] text [3]", or a paragraph
     * "text [4,5]". Paragraphs stand apart from their neighbours by a blank line, as Markdown needs.
     */
    fun bulletLines(bullets: List<Bullet>, withRefs: Boolean = true): String = buildString {
        bullets.forEachIndexed { i, b ->
            if (i > 0) append(if (b.para || bullets[i - 1].para) "\n\n" else "\n")
            val refs = if (withRefs) " [${b.refs.joinToString(",")}]" else ""
            append(if (b.para) b.text + refs else "- ${if (b.task) "[ ] " else ""}${b.text}$refs")
        }
    }

    /** A stored clean version as the user edits it: the lines without the title and sources. */
    fun editable(stored: String): String = bulletLines(bulletsFromStored(stored), withRefs = false)

    /**
     * The edited text back as lines: "- " bullets, "- [ ] " tasks, anything else a paragraph. Each keeps
     * the sources of the old line with the same text, or of the old line in its place; new lines past
     * the end take the last line's. So every line stays linked to the exact words.
     */
    fun fromEditable(text: String, old: List<Bullet>): List<Bullet> =
        text.replace("\r\n", "\n").lines().map { it.trim() }.filter { it.isNotEmpty() }.mapIndexed { i, l ->
            val task = l.startsWith("- [ ] ") || l.startsWith("- [x] ")
            val bullet = !task && (l.startsWith("- ") || l.startsWith("* ") || l.startsWith("• "))
            val body = when { task -> l.drop(6); bullet -> l.drop(2); else -> l }.trim()
            val refs = old.firstOrNull { it.text == body }?.refs ?: old.getOrNull(i)?.refs ?: old.lastOrNull()?.refs ?: listOf(1)
            Bullet(body, refs, task = task, para = !task && !bullet)
        }.filter { it.text.isNotEmpty() }

    /** The note with [old] (standing once, on lines of its own) replaced by [new]; null if it is not there like that. */
    fun replaceWords(note: String, old: String, new: String): String? {
        val (front, body) = NoteText.split(note)
        val hits = lineOccurrences(body, old)
        if (old.isBlank() || hits.size != 1) return null
        return front + body.substring(0, hits[0]) + new + body.substring(hits[0] + old.length)
    }

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
                // A cleaned paragraph: its sources hidden like a bullet's.
                !t.startsWith(">") && !t.startsWith("#") && REFS.containsMatchIn(t) -> t.replace(REFS, "")
                else -> line
            }
        }
        return out.joinToString("\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    /** [stored] text as plain lines for a card: the title, then "• " bullets or "☐ " tasks. */
    fun storedForDisplay(s: String): String = parseStored(s).joinToString("\n") {
        when { it.title -> it.text; it.task -> "☐ ${it.text}"; it.para -> it.text; else -> "• ${it.text}" }
    }

    /** A line of [stored] text, ready to show: its sources, and whether it is a title, task or paragraph. */
    data class Line(val text: String, val refs: List<Int>, val title: Boolean = false, val task: Boolean = false, val para: Boolean = false)

    fun parseStored(s: String): List<Line> = s.lines().filter { it.isNotBlank() }.map { l ->
        when {
            l.startsWith("# ") -> Line(l.removePrefix("# ").trim(), emptyList(), title = true)
            else -> {
                val para = !l.startsWith("- ")
                var body = l.removePrefix("- ")
                val task = !para && body.startsWith("[ ] ").also { if (it) body = body.removePrefix("[ ] ") }
                val m = REFS.find(body)
                Line(if (m != null) body.substring(0, m.range.first).trim() else body.trim(),
                    m?.let { parseRefs(it.groupValues[1]) } ?: emptyList(), task = task, para = para)
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
