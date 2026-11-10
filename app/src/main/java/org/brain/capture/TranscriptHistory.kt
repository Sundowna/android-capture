package org.brain.capture

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

data class HistoryEntry(
    val atMs: Long,
    val transcript: String,
    val audioSeconds: Double,
    val modelLoadMs: Long,
    val transcribeMs: Long,
    val chunks: Int,
    val notePath: String?,
) {
    val realTimeFactor: Double get() = if (audioSeconds > 0) transcribeMs / 1000.0 / audioSeconds else 0.0
}

/**
 * Recent transcripts, newest first, kept in app-private storage (one JSON object per line).
 * Only for showing and copying in the app; the vault note remains the real record.
 */
object TranscriptHistory {
    private const val MAX = 200
    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()
    @Volatile private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "history.jsonl")

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = file(ctx)
        if (!f.exists()) return
        _entries.value = f.readLines().mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                HistoryEntry(
                    o.getLong("at"), o.getString("text"), o.getDouble("audio"), o.getLong("load"),
                    o.getLong("tx"), o.getInt("chunks"), o.optString("note").ifEmpty { null },
                )
            }.getOrNull()
        }.asReversed().take(MAX)
    }

    @Synchronized
    fun add(ctx: Context, e: HistoryEntry) {
        load(ctx)
        if (e.transcript.isBlank()) return
        _entries.value = (listOf(e) + _entries.value).take(MAX)
        val f = file(ctx)
        f.appendText(json(e))
        if (_entries.value.size >= MAX && f.length() > 512_000) rewrite(f)
    }

    /** Adds [more] to the entry for [notePath] and moves it to the top. Returns false if there is none. */
    @Synchronized
    fun extend(ctx: Context, notePath: String, more: HistoryEntry): Boolean {
        load(ctx)
        val old = _entries.value.firstOrNull { it.notePath == notePath } ?: return false
        if (more.transcript.isBlank()) return true
        val merged = old.copy(
            atMs = more.atMs,
            transcript = old.transcript + "\n\n" + more.transcript,
            audioSeconds = old.audioSeconds + more.audioSeconds,
            modelLoadMs = more.modelLoadMs,
            transcribeMs = more.transcribeMs,
            chunks = old.chunks + more.chunks,
        )
        _entries.value = listOf(merged) + _entries.value.filter { it !== old }
        rewrite(file(ctx))
        return true
    }

    private fun json(h: HistoryEntry) = JSONObject().put("at", h.atMs).put("text", h.transcript)
        .put("audio", h.audioSeconds).put("load", h.modelLoadMs).put("tx", h.transcribeMs)
        .put("chunks", h.chunks).put("note", h.notePath ?: "").toString() + "\n"

    private fun rewrite(f: File) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(_entries.value.asReversed().joinToString("") { json(it) })
        tmp.renameTo(f)
    }
}
