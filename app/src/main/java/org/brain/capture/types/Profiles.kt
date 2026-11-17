package org.brain.capture.types

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A capture type ("agent"): what the widget button, home-screen icon or chip is called, and the
 * instruction Gemma follows for it. The id is stored on each capture, so it never changes.
 */
data class CaptureProfile(
    val id: String,
    val name: String,
    val subtitle: String,
    /** One of [Profiles.ICONS]. */
    val icon: String,
    /** What Gemma should do. The rules for sources, title and format are added by the app. */
    val instruction: String,
    /** Make a "- [ ]" checklist (with due dates) instead of bullets. */
    val checklist: Boolean,
    /** Stays on the phone: never written to the vault. Fixed when the type is created. */
    val private: Boolean,
    val onWidget: Boolean,
) {
    val builtIn: Boolean get() = id in Profiles.BUILT_IN
}

object Profiles {
    const val IDEA = "idea"
    const val TASK = "task"
    const val MEETING = "meeting"
    const val PRIVATE = "private"
    val BUILT_IN = setOf(IDEA, TASK, MEETING, PRIVATE)
    val ICONS = listOf("idea", "task", "people", "mic", "lock", "star", "book", "cart")

    /** Most widget buttons there is room for (Open Brain takes one of them when it is shown). */
    const val WIDGET_SLOTS = 4

    const val IDEA_INSTRUCTION = """Rewrite this voice note as clean bullet points.
Rules: remove filler words and repeats, fix grammar, put related points together.
Use only what the speaker said. Add no facts, opinions or conclusions.
Keep the language of the transcript."""

    const val TASK_INSTRUCTION = """Rewrite this voice note as a checklist of tasks.
Rules: one task per line, short and starting with a verb; remove filler words and repeats.
Leave out remarks that are not tasks, such as "okay", "task list" or questions to yourself.
Use only what the speaker said. Add no tasks, facts or opinions.
Keep the language of the transcript."""

    const val MEETING_INSTRUCTION = """Rewrite this voice note about a meeting as clean bullet points.
Rules: start with who was there if it is said, then what was decided, then open points and who does them.
Remove filler words and repeats. Use only what the speaker said. Add no facts, names or conclusions.
Keep the language of the transcript."""

    private val DEFAULTS = listOf(
        CaptureProfile(IDEA, "Idea", "tidy into bullets", "idea", IDEA_INSTRUCTION, checklist = false, private = false, onWidget = true),
        CaptureProfile(TASK, "Task", "checklist + due date", "task", TASK_INSTRUCTION, checklist = true, private = false, onWidget = true),
        CaptureProfile(MEETING, "Meeting", "who, decisions, to-dos", "people", MEETING_INSTRUCTION, checklist = false, private = false, onWidget = false),
        CaptureProfile(PRIVATE, "Private", "stays on device", "lock", IDEA_INSTRUCTION, checklist = false, private = true, onWidget = true),
    )

    fun default(id: String): CaptureProfile? = DEFAULTS.firstOrNull { it.id == id }

    private val state = MutableStateFlow<List<CaptureProfile>?>(null)

    /** All types in their order; live, for screens that should update when one is edited. */
    fun flow(ctx: Context): StateFlow<List<CaptureProfile>?> { all(ctx); return state }

    fun all(ctx: Context): List<CaptureProfile> {
        state.value?.let { return it }
        val raw = prefs(ctx).getString(KEY, null)
        val list = raw?.let { runCatching { parse(it) }.getOrNull() }?.takeIf { it.isNotEmpty() } ?: DEFAULTS
        state.value = list
        return list
    }

    /** The type for a capture; unknown or deleted ids fall back to Idea (or Private for private ones). */
    fun get(ctx: Context, id: String?): CaptureProfile {
        val list = all(ctx)
        return list.firstOrNull { it.id == id }
            ?: if (isPrivateId(id)) list.firstOrNull { it.private } ?: DEFAULTS.last() else list.firstOrNull { it.id == IDEA } ?: DEFAULTS.first()
    }

    fun save(ctx: Context, list: List<CaptureProfile>) {
        prefs(ctx).edit().putString(KEY, JSONArray(list.map(::toJson)).toString()).apply()
        state.value = list
    }

    fun put(ctx: Context, p: CaptureProfile) {
        val list = all(ctx)
        save(ctx, if (list.any { it.id == p.id }) list.map { if (it.id == p.id) p else it } else list + p)
    }

    fun delete(ctx: Context, id: String) {
        val rest = all(ctx).filterNot { it.id == id }
        if (rest.isNotEmpty()) save(ctx, rest)
    }

    fun move(ctx: Context, id: String, by: Int) {
        val list = all(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = (i + by).coerceIn(0, list.size - 1)
        if (i < 0 || i == j) return
        list.add(j, list.removeAt(i))
        save(ctx, list)
    }

    /** A new type; private ones get an id that marks them private for good. */
    fun newProfile(private: Boolean) = CaptureProfile(
        id = (if (private) "$PRIVATE-" else "c-") + UUID.randomUUID().toString().take(8),
        name = "", subtitle = "", icon = if (private) "lock" else "star",
        instruction = IDEA_INSTRUCTION, checklist = false, private = private, onWidget = false,
    )

    fun isPrivateId(id: String?) = id == PRIVATE || id?.startsWith("$PRIVATE-") == true

    /** Whether the widget shows an "Open Brain" button. */
    fun widgetOpenBrain(ctx: Context) = prefs(ctx).getBoolean(KEY_OPEN, true)
    fun setWidgetOpenBrain(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean(KEY_OPEN, on).apply(); state.value = all(ctx).toList() }

    /** What the widget shows, in order: types first, then Open Brain (null) if it is on. */
    fun widgetSlots(ctx: Context): List<CaptureProfile?> {
        val open = widgetOpenBrain(ctx)
        val types = all(ctx).filter { it.onWidget }.take(if (open) WIDGET_SLOTS - 1 else WIDGET_SLOTS)
        return types + if (open) listOf(null) else emptyList()
    }

    /** The type a plain one-tap icon or the quick settings tile uses: the first one on the widget. */
    fun primary(ctx: Context): CaptureProfile = all(ctx).firstOrNull { it.onWidget && !it.private } ?: get(ctx, IDEA)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private const val KEY = "capture_profiles"
    private const val KEY_OPEN = "widget_open_brain"

    private fun toJson(p: CaptureProfile) = JSONObject().put("id", p.id).put("name", p.name).put("subtitle", p.subtitle)
        .put("icon", p.icon).put("instruction", p.instruction).put("checklist", p.checklist)
        .put("private", p.private).put("onWidget", p.onWidget)

    private fun parse(raw: String): List<CaptureProfile> {
        val a = JSONArray(raw)
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            CaptureProfile(o.getString("id"), o.optString("name"), o.optString("subtitle"), o.optString("icon", "star"),
                o.optString("instruction", IDEA_INSTRUCTION), o.optBoolean("checklist"), o.optBoolean("private"), o.optBoolean("onWidget"))
        }
    }
}
