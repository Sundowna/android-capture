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
        val o = JSONObject()
            .put("at", e.atMs).put("text", e.transcript).put("audio", e.audioSeconds)
            .put("load", e.modelLoadMs).put("tx", e.transcribeMs).put("chunks", e.chunks)
            .put("note", e.notePath ?: "")
        val f = file(ctx)
        f.appendText(o.toString() + "\n")
        // Keep the file from growing forever.
        if (_entries.value.size >= MAX && f.length() > 512_000) {
            f.writeText(_entries.value.asReversed().joinToString("") { h ->
                JSONObject().put("at", h.atMs).put("text", h.transcript).put("audio", h.audioSeconds)
                    .put("load", h.modelLoadMs).put("tx", h.transcribeMs).put("chunks", h.chunks)
                    .put("note", h.notePath ?: "").toString() + "\n"
            })
        }
    }
}
