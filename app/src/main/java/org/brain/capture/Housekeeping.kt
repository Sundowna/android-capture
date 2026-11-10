package org.brain.capture

import android.content.Context
import android.text.format.Formatter
import org.brain.capture.data.CaptureDb
import org.brain.capture.data.CaptureStates
import java.io.File

/**
 * What the app keeps on the phone, and removal of leftovers. Never deletes a recording or share
 * that has not reached the vault.
 */
object Housekeeping {
    data class Usage(val model: Long, val staging: Long, val thumbs: Long, val database: Long)

    fun usage(ctx: Context): Usage {
        val f = ctx.filesDir
        val db = ctx.getDatabasePath("captures.db")
        return Usage(
            model = size(File(f, "whisper")),
            staging = size(File(f, "staging")) + size(File(f, "shares")),
            thumbs = size(File(f, "thumbs")),
            database = listOf(db, File(db.path + "-wal"), File(db.path + "-shm")).sumOf { it.length() },
        )
    }

    /** Returns a short summary of what was freed. */
    suspend fun clean(ctx: Context): String {
        val dao = CaptureDb.get(ctx).dao()
        val f = ctx.filesDir
        var freed = 0L
        fun drop(file: File) { freed += size(file); file.deleteRecursively() }

        // Previews of shares that were discarded or no longer exist.
        File(f, "thumbs").listFiles()?.forEach { t ->
            val c = dao.get(t.nameWithoutExtension.substringBeforeLast('-'))
            if (c == null || c.state == "discarded") drop(t)
        }
        // Share folders whose content is already in the vault or was discarded.
        File(f, "shares").listFiles()?.forEach { d ->
            val c = dao.get(d.name)
            if (c == null || c.state == CaptureStates.SAVED || c.state == "discarded") drop(d)
        }
        // Encoder output and old append markers without their recording.
        File(f, "staging").listFiles()?.forEach { s ->
            if (s.extension != "wav" && !File(s.parentFile, s.nameWithoutExtension + ".wav").exists()) drop(s)
        }
        // Interrupted model import, imported history file, stray temp files.
        listOf(File(f, "whisper.importing"), File(f, "history.jsonl.imported")).forEach { if (it.exists()) drop(it) }
        f.listFiles { x -> x.isFile && x.name.endsWith(".tmp") }?.forEach(::drop)
        dao.deleteDiscarded()

        return if (freed == 0L) "Nothing to clean up." else "Freed ${Formatter.formatShortFileSize(ctx, freed)}."
    }

    private fun size(f: File): Long = if (!f.exists()) 0 else f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
}
