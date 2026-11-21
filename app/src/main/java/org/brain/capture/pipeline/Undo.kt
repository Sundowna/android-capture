package org.brain.capture.pipeline

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.brain.capture.cleanup.CleanupStep
import org.brain.capture.data.Capture
import org.brain.capture.data.CaptureDb
import org.brain.capture.share.AppScope
import java.io.File

/**
 * The home screen's Undo: the last thing done, whatever it was — a new note, something added to a
 * note, a re-clean, or a delete. One step only; the next action replaces it, and it lapses after
 * [KEEP_MS] (or when the app is closed), so an old action is never undone by surprise.
 */
object Undo {
    sealed class Action(val id: String, val label: String) {
        /** Swiped away; still within its Undo bar, nothing deleted yet. */
        class PendingDelete(id: String) : Action(id, "Undo delete")
        /** Deleted: [moves] = (path in .trash, original path); [held] = (kept private file, original file). */
        class Deleted(id: String, val rows: List<Capture>, val moves: List<Pair<String, String>>, val held: List<Pair<String, String>>) :
            Action(id, "Undo delete")
        class Created(id: String) : Action(id, "Undo new note")
        class Added(id: String) : Action(id, "Undo addition")
        class Recleaned(noteId: String) : Action(noteId, "Undo re-clean")
    }

    const val KEEP_MS = 10 * 60_000L
    private const val TAG = "Undo"
    private val _last = MutableStateFlow<Action?>(null)
    val last: StateFlow<Action?> = _last
    private var expiry: Job? = null

    fun holdDir(ctx: Context) = File(ctx.filesDir, "undo-hold").apply { mkdirs() }

    @Synchronized
    fun record(a: Action) {
        val old = _last.value
        if (old is Action.Deleted && old !== a) purge(old)
        _last.value = a
        expiry?.cancel()
        expiry = AppScope.launch { delay(KEEP_MS); synchronized(this@Undo) { if (_last.value === a) clear() } }
        Log.i(TAG, "${a.label} available for ${a.id}")
    }

    /** Whether [id] was swiped and is still the last action (so its real delete takes over the Undo). */
    fun isPending(id: String) = (_last.value as? Action.PendingDelete)?.id == id

    /** Undone elsewhere (the note screen, the Undo bar): no longer offered here. */
    @Synchronized
    fun forget(id: String) { if (_last.value?.id == id) clear() }

    /** Something that cannot be undone happened (a hand edit): nothing older is offered any more. */
    fun drop() = clear()

    @Synchronized
    private fun clear() { (_last.value as? Action.Deleted)?.let(::purge); _last.value = null; expiry?.cancel() }

    /** Held private audio is deleted for good once its delete can no longer be undone. */
    private fun purge(d: Action.Deleted) = d.held.forEach { File(it.first).delete() }

    /** At app start nothing can be undone: drop audio held by a delete from before. */
    fun cleanupOnStart(ctx: Context) { if (_last.value == null) holdDir(ctx).listFiles()?.forEach { it.delete() } }

    /** Takes back the last action. Returns a short message for the screen. */
    suspend fun undo(ctx: Context): String {
        val a = synchronized(this) { _last.value?.also { _last.value = null; expiry?.cancel() } } ?: return "Nothing to undo"
        val dao = CaptureDb.get(ctx).dao()
        return runCatching {
            when (a) {
                is Action.PendingDelete -> { org.brain.capture.ui.SwipeDeletes.undo(a.id); "Note kept" }
                is Action.Deleted -> { Notes.restore(ctx, a); "Note restored" }
                is Action.Created -> {
                    val c = dao.get(a.id) ?: error("The note is already gone")
                    Notes.delete(ctx, c, undoable = false)
                    if (c.isPrivate) "Private note deleted" else "New note moved to the vault's .trash"
                }
                is Action.Added -> {
                    val c = dao.get(a.id) ?: error("The addition is already gone")
                    Notes.deleteAddition(ctx, c); "Addition taken out of the note"
                }
                is Action.Recleaned -> {
                    if (CleanupStep.lastReclean.value?.noteId != a.id) error("This re-clean was already undone")
                    CleanupStep.undoReclean(ctx); "Previous clean version back"
                }
            }
        }.getOrElse { t ->
            Log.w(TAG, "Undo failed", t)
            if (a is Action.Deleted) synchronized(this) { if (_last.value == null) _last.value = a } // still undoable later
            "Could not undo: ${t.message}"
        }.also { org.brain.capture.ui.BrainWidget.refresh(ctx) }
    }
}
