package org.brain.capture.vault

/** Pure helpers for reading notes (no Android dependencies, unit-tested). */
object NoteText {
    private val EMBED_LINE = Regex("""^\s*!\[\[[^\]]*]]\s*$""")
    private val ID = Regex("""(?m)^capture_id:\s*(\S+)\s*$""")

    /** Splits a note into its frontmatter block (including the --- lines and trailing newline) and body. */
    fun split(note: String): Pair<String, String> {
        val text = note.replace("\r\n", "\n")
        if (!text.startsWith("---\n")) return "" to text
        val end = text.indexOf("\n---\n", 4)
        if (end < 0) return "" to text
        val cut = end + "\n---\n".length
        return text.substring(0, cut) to text.substring(cut)
    }

    fun captureId(note: String): String? = ID.find(split(note).first)?.groupValues?.get(1)

    /** What a card shows and copies: the body without frontmatter and without audio embed lines. */
    fun display(note: String): String =
        split(note).second.lines().filterNot { EMBED_LINE.matches(it) }
            .joinToString("\n").replace(Regex("""\n{3,}"""), "\n\n").trim()

    /** The note with its body replaced; frontmatter is kept exactly as it was. */
    fun withBody(note: String, body: String): String {
        val front = split(note).first
        return front + (if (front.isNotEmpty()) "\n" else "") + body.trimEnd() + "\n"
    }
}
