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
    /** Short paragraphs and bullet lists mixed, as the content needs (not with [checklist]). */
    val mixed: Boolean = false,
    /**
     * The built-in instruction this copy started from. While [instruction] still equals it, the user
     * has not edited it, so an improved built-in text replaces it.
     */
    val base: String? = null,
) {
    val builtIn: Boolean get() = id in Profiles.BUILT_IN
}

object Profiles {
    const val IDEA = "idea"
    const val TASK = "task"
    const val MEETING = "meeting"
    const val PRIVATE = "private"
    val BUILT_IN = setOf(IDEA, TASK, MEETING, PRIVATE)
    val ICONS = listOf(
        "idea", "task", "bug", "wrench", "sparkle", "rocket", "code", "flag",
        "target", "pen", "chat", "people", "phone", "mic", "calendar", "clock",
        "pin", "book", "cart", "money", "gift", "home", "car", "plane",
        "camera", "music", "heart", "health", "leaf", "question", "lock", "star",
    )

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

    /** The general cleaner: everything said, in order, minus the noise of speaking. */
    const val VOICE_NOTE_INSTRUCTION = """Clean up this voice note so it reads well, without changing what it says.
Rules: keep every point and keep the speaker's order.
Write connected thoughts as short paragraphs, and lists, steps or separate items as bullet points.
Remove filler words, false starts, repeats and sign-offs such as "okay" or "that is it"; when the speaker corrects themselves, keep only the corrected version.
Keep words and remarks that change the meaning, such as not, maybe, only, almost, probably, or "not what I said before".
Keep names, numbers, dates and examples exactly.
When the speaker counts things ("one…, two…", "first…, then…"), write a short line saying what they are for, then each item as its own bullet without the counting word.
Do not summarise or shorten the content. Add no facts, opinions or conclusions.
Keep the language of the transcript."""

    /**
     * Earlier versions of built-in instructions. A stored type still holding one of them word for word
     * was never edited by the user, so it gets the current text; edited ones are left alone.
     */
    private val OLD_INSTRUCTIONS = mapOf(
        "c-voicenote" to listOf("""Clean up this voice note so it reads well, without changing what it says.
Rules: keep every point and keep the speaker's order; one point per bullet.
Remove filler words, false starts and repeats; when the speaker corrects themselves, keep only the corrected version.
Keep words that change the meaning, such as not, maybe, only, almost, probably.
Keep names, numbers, dates and examples exactly.
When the speaker counts things ("one…, two…", "first…, then…"), give each its own bullet.
Do not summarise or shorten the content. Add no facts, opinions or conclusions.
Keep the language of the transcript.""", """Clean up this voice note so it reads well, without changing what it says.
Rules: keep every point and keep the speaker's order; one point per bullet.
Remove filler words, false starts, repeats and sign-offs such as "okay" or "that is it"; when the speaker corrects themselves, keep only the corrected version.
Keep words and remarks that change the meaning, such as not, maybe, only, almost, probably, or "not what I said before".
Keep names, numbers, dates and examples exactly.
When the speaker counts things ("one…, two…", "first…, then…"), give each its own bullet without the counting word, starting with what they belong to, e.g. "Trip to Bern: book the train".
Do not summarise or shorten the content. Add no facts, opinions or conclusions.
Keep the language of the transcript."""),
        "c-fixfeature" to listOf("""This voice note is about an app or a tool: things to fix and features to add.
Rewrite it as a checklist. Start each line with "Fix: " for something broken or wrong, or "Feature: " for something new or better.
Rules: one fix or feature per line; say what to change and where, using the speaker's own names for screens, buttons and apps; keep details such as numbers, names and steps.
Leave out greetings, thinking aloud and remarks that are not fixes or features.
Use only what the speaker said. Add no fixes, features or solutions.
Keep the language of the transcript."""),
        "c-fix" to listOf("""This voice note lists things to fix in an app or a tool.
Rewrite it as a checklist: one problem per line, saying what goes wrong and where, and what it should do instead if the speaker says so.
Use the speaker's own names for screens, buttons and apps; keep details such as numbers and steps.
Leave out greetings, thinking aloud and remarks that are not fixes.
Use only what the speaker said. Add no causes or solutions.
Keep the language of the transcript."""),
        "c-feature" to listOf("""This voice note lists features to add or change in an app or a tool.
Rewrite it as a checklist: one feature per line, starting with a verb, and saying where it goes.
Use the speaker's own names for screens, buttons and apps; keep details such as numbers, examples and order.
Leave out greetings, thinking aloud and remarks that are not features.
Use only what the speaker said. Add no features or details.
Keep the language of the transcript."""),
    )

    /*
     * Fix / Feature types: feedback on an app, written for whoever builds it (a developer or a coding
     * agent). Each point is one line that can be acted on without the recording: where it happens
     * (screen, button, how you got there), what happens, what should happen. What already works and
     * what the speaker asks are kept too — they say what not to change and what to answer.
     * Tuned on the real feedback notes of 16–17 Nov 2026.
     */
    private const val FEEDBACK_RULES = """Where: the screen, button or flow in the speaker's own words, with the steps that lead there.
One line per point, in the speaker's order: a point spread over several sentences becomes one line, with its segment numbers together, e.g. [3,4,6].
Write short, plain lines: drop spoken filler inside a point ("you know", "or whatever", "let me just"), but keep every detail exactly: numbers, names, order, and how it should look or feel ("smoother", "softer", "a bit slower").
In this app "node" is a mishearing of "note", "Whiskr" or "Wispers" of "Whisper", and "keen" of "clean".
Leave out everything else: filler, greetings, thinking aloud, and remarks that are not a fix, feature, praise or question.
Use only what the speaker said. Add no fixes, features, causes or solutions.
Keep the language of the transcript."""

    const val FIX_FEATURE_INSTRUCTION = """This voice note is feedback on an app: what is broken, what to add, what already works, and questions.
Write each point as one of these lines:
- Fix: <where> — <what goes wrong>; should <what it should do instead>
- Feature: <where> — <what to add or change, with every detail given>
- Works: <only what the speaker praises or confirms works>
- Question: <what the speaker asks>
Leave out "; should …" when the speaker does not say what it should do.
$FEEDBACK_RULES"""

    const val FIX_INSTRUCTION = """This voice note is about things that are broken or wrong in an app.
Write each point as one of these lines:
- Fix: <where> — <what goes wrong>; should <what it should do instead>
- Works: <only what the speaker praises or confirms works>
- Question: <what the speaker asks>
Leave out "; should …" when the speaker does not say what it should do.
$FEEDBACK_RULES"""

    const val FEATURE_INSTRUCTION = """This voice note is about features to add or change in an app.
Write each point as one of these lines:
- Feature: <where> — <what to add or change, with every detail given>
- Works: <only what the speaker praises or confirms works>
- Question: <what the speaker asks, e.g. whether it is doable>
Mark a feature the speaker leaves for later with "(later)" at the end.
$FEEDBACK_RULES"""

    /** Types added after the first release: put into existing lists once, after that they are ordinary, deletable types. */
    private val ADDED = listOf(
        // Bullets, not checklist: only Fix and Feature lines get a box; Works and Question lines do not.
        CaptureProfile("c-fixfeature", "Fix / Feature", "sorted into fixes and features", "wrench", FIX_FEATURE_INSTRUCTION, checklist = false, private = false, onWidget = false),
        CaptureProfile("c-fix", "Fix", "what's broken, where", "bug", FIX_INSTRUCTION, checklist = false, private = false, onWidget = false),
        CaptureProfile("c-feature", "Feature", "what to add or change", "sparkle", FEATURE_INSTRUCTION, checklist = false, private = false, onWidget = false),
        CaptureProfile("c-voicenote", "Voice note", "cleaned up, nothing left out", "mic", VOICE_NOTE_INSTRUCTION, checklist = false, private = false, onWidget = false, mixed = true),
    )
    private const val KEY_ADDED = "capture_profiles_added"

    private val DEFAULTS = listOf(
        CaptureProfile(IDEA, "Idea", "tidy into bullets", "idea", IDEA_INSTRUCTION, checklist = false, private = false, onWidget = true),
        CaptureProfile(TASK, "Task", "checklist + due date", "task", TASK_INSTRUCTION, checklist = true, private = false, onWidget = true),
        CaptureProfile(MEETING, "Meeting", "who, decisions, to-dos", "people", MEETING_INSTRUCTION, checklist = false, private = false, onWidget = false),
        CaptureProfile(PRIVATE, "Private", "stays on device", "lock", IDEA_INSTRUCTION, checklist = false, private = true, onWidget = true),
    ) + ADDED

    fun default(id: String): CaptureProfile? = DEFAULTS.firstOrNull { it.id == id }

    private val state = MutableStateFlow<List<CaptureProfile>?>(null)

    /** All types in their order; live, for screens that should update when one is edited. */
    fun flow(ctx: Context): StateFlow<List<CaptureProfile>?> { all(ctx); return state }

    fun all(ctx: Context): List<CaptureProfile> {
        state.value?.let { return it }
        val raw = prefs(ctx).getString(KEY, null)
        var list = raw?.let { runCatching { parse(it) }.getOrNull() }?.takeIf { it.isNotEmpty() } ?: DEFAULTS
        val seen = prefs(ctx).getStringSet(KEY_ADDED, emptySet())!!
        val missing = ADDED.filter { it.id !in seen }
        // Untouched built-in instructions follow improvements to their default text.
        val upgraded = list.map { p ->
            val d = default(p.id) ?: return@map p
            val untouched = p.instruction == p.base || OLD_INSTRUCTIONS[p.id]?.contains(p.instruction) == true
            when {
                p.instruction == d.instruction -> if (p.base != d.instruction) p.copy(base = d.instruction) else p
                untouched -> p.copy(instruction = d.instruction, base = d.instruction, mixed = d.mixed, checklist = d.checklist)
                else -> p
            }
        }
        val changed = upgraded != list
        list = upgraded
        if (missing.isNotEmpty() || changed) {
            list = list + missing.filter { m -> list.none { it.id == m.id } }
            prefs(ctx).edit().putString(KEY, JSONArray(list.map(::toJson)).toString())
                .putStringSet(KEY_ADDED, seen + missing.map { it.id }).apply()
        }
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
        .put("private", p.private).put("onWidget", p.onWidget).put("mixed", p.mixed)
        .apply { p.base?.let { put("base", it) } }

    private fun parse(raw: String): List<CaptureProfile> {
        val a = JSONArray(raw)
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            CaptureProfile(o.getString("id"), o.optString("name"), o.optString("subtitle"), o.optString("icon", "star"),
                o.optString("instruction", IDEA_INSTRUCTION), o.optBoolean("checklist"), o.optBoolean("private"), o.optBoolean("onWidget"),
                o.optBoolean("mixed", default(o.getString("id"))?.mixed ?: false),
                o.optString("base").ifEmpty { null })
        }
    }
}
