package org.brain.capture.share

/** A shared web link. */
data class SharedLink(val url: String, val title: String?, val extraText: String?)

/** Pure parsing of shared text (no Android dependencies, unit-tested). */
object ShareParse {
    private val URL = Regex("""https?://[^\s<>"]+""")
    private const val TRAILING = ".,;:!?)]}'\""

    /**
     * Finds a link in shared text. Browsers send the URL as text and the page title as subject;
     * some apps send "Title\nURL" or "Title URL" as text, or a quote followed by the link.
     */
    fun link(text: String?, subject: String?): SharedLink? {
        val t = text?.trim().orEmpty()
        val m = URL.find(t) ?: return null
        val url = m.value.trimEnd { it in TRAILING }
        val rest = t.removeRange(m.range).trim().ifEmpty { null }
        var title = subject?.trim()?.ifEmpty { null }
        var extra = rest
        if (title == null && rest != null && '\n' !in rest && rest.length <= 200) { title = rest; extra = null }
        if (extra != null && extra == title) extra = null
        return SharedLink(url, title, extra)
    }

    fun domain(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?').removePrefix("www.")

    /** "Image", "PDF" or "File", used in note names. */
    fun fileKind(mimes: List<String?>): String = when {
        mimes.isNotEmpty() && mimes.all { it?.startsWith("image/") == true } -> "Image"
        mimes.isNotEmpty() && mimes.all { it == "application/pdf" } -> "PDF"
        else -> "File"
    }
}
