package org.brain.capture.ui

import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Icon
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon as MIcon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.brain.capture.R
import org.brain.capture.types.CaptureProfile
import org.brain.capture.types.Profiles

/** Drawable for a type's icon name. */
fun typeIcon(name: String): Int = when (name) {
    "idea" -> R.drawable.t_idea; "task" -> R.drawable.t_task; "bug" -> R.drawable.t_bug; "wrench" -> R.drawable.t_wrench
    "sparkle" -> R.drawable.t_sparkle; "rocket" -> R.drawable.t_rocket; "code" -> R.drawable.t_code; "flag" -> R.drawable.t_flag
    "target" -> R.drawable.t_target; "pen" -> R.drawable.t_pen; "chat" -> R.drawable.t_chat; "people" -> R.drawable.t_people
    "phone" -> R.drawable.t_phone; "mic" -> R.drawable.t_mic; "calendar" -> R.drawable.t_calendar; "clock" -> R.drawable.t_clock
    "pin" -> R.drawable.t_pin; "book" -> R.drawable.t_book; "cart" -> R.drawable.t_cart; "money" -> R.drawable.t_money
    "gift" -> R.drawable.t_gift; "home" -> R.drawable.t_home; "car" -> R.drawable.t_car; "plane" -> R.drawable.t_plane
    "camera" -> R.drawable.t_camera; "music" -> R.drawable.t_music; "heart" -> R.drawable.t_heart; "health" -> R.drawable.t_health
    "leaf" -> R.drawable.t_leaf; "question" -> R.drawable.t_question; "lock" -> R.drawable.t_lock
    else -> R.drawable.t_star
}

/**
 * Settings → Capture types: each type ("agent") with its name, icon and the instruction Gemma
 * follows; which ones the widget shows; and a one-tap home-screen icon for any of them.
 * [widgetOnly] is the short version the widget's own "Edit widget" opens.
 */
/** Which part of the type settings to show: Settings has a page for each; the widget's own editor shows both. */
enum class TypesPart { Types, Widget, Both }

@Composable
fun CaptureTypesSettings(widgetOnly: Boolean = false, part: TypesPart = if (widgetOnly) TypesPart.Both else TypesPart.Types) {
    val ctx = LocalContext.current
    val profiles by remember { Profiles.flow(ctx) }.collectAsState()
    val list = profiles ?: return
    var editing by remember { mutableStateOf<CaptureProfile?>(null) }
    var openBrain by remember { mutableStateOf(Profiles.widgetOpenBrain(ctx)) }
    val onWidget = list.count { it.onWidget }
    val room = Profiles.WIDGET_SLOTS - if (openBrain) 1 else 0
    val switches = part != TypesPart.Types
    val reorder = part == TypesPart.Types
    var primaryId by remember { mutableStateOf(Profiles.primary(ctx).id) }

    Text(when (part) {
        TypesPart.Both -> "Choose the widget's buttons. Tap a type to edit its name, icon or AI instruction."
        TypesPart.Widget -> "The Brain widget shows up to $room buttons. For a single button, add the 1×1 \"Brain button\" widget instead."
        TypesPart.Types -> "Each type has its own instruction for the AI. Tap one to edit it; ↑ moves it up."
    }, fontSize = 13.sp, color = Ink.TextDim)
    if (part == TypesPart.Types) DefaultTypePicker(list, primaryId) { Profiles.setPrimary(ctx, it); primaryId = it; Shortcuts.update(ctx) }
    list.forEachIndexed { i, p ->
        // On the widget page a tap switches the button; elsewhere it opens the type.
        val canSwitch = p.onWidget || onWidget < room
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable {
            if (part == TypesPart.Widget) { if (canSwitch) { Profiles.put(ctx, p.copy(onWidget = !p.onWidget)); Shortcuts.update(ctx); BrainWidget.refresh(ctx) } }
            else editing = p
        }.heightIn(min = 60.dp)
            .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(if (p.id == primaryId) Ink.Amber else Ink.Well), contentAlignment = Alignment.Center) {
                MIcon(painterResource(typeIcon(p.icon)), null, Modifier.size(20.dp), tint = if (p.id == primaryId) Ink.OnAmber else Ink.TextStrong)
            }
            Column(Modifier.weight(1f)) {
                Text(p.name.ifBlank { "Unnamed" }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink.Text)
                Text(p.subtitle.ifBlank { listOfNotNull(if (p.private) "stays on phone" else null, if (p.checklist) "checklist" else null)
                    .joinToString(" · ") }, fontSize = 12.sp, color = Ink.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (reorder && i > 0) TextButton(onClick = { Profiles.move(ctx, p.id, -1); Shortcuts.update(ctx); BrainWidget.refresh(ctx) }) { Text("↑") }
            if (switches) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Switch(checked = p.onWidget, enabled = canSwitch, onCheckedChange = {
                    Profiles.put(ctx, p.copy(onWidget = it)); Shortcuts.update(ctx); BrainWidget.refresh(ctx)
                }, colors = SwitchDefaults.colors(checkedTrackColor = Ink.Amber, checkedThumbColor = Ink.OnAmber))
                if (part == TypesPart.Both) Text("widget", fontSize = 10.sp, color = Ink.TextDim)
            }
        }
    }
    if (switches) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Open Brain button", fontSize = 15.sp, color = Ink.Text)
            Text("Opens your notes from the widget", fontSize = 12.sp, color = Ink.TextDim)
        }
        Switch(checked = openBrain, onCheckedChange = {
            // Turning it on needs a free slot: the last type on the widget makes room.
            if (it && onWidget >= Profiles.WIDGET_SLOTS) list.lastOrNull { p -> p.onWidget }?.let { p -> Profiles.put(ctx, p.copy(onWidget = false)) }
            openBrain = it; Profiles.setWidgetOpenBrain(ctx, it); BrainWidget.refresh(ctx)
        }, colors = SwitchDefaults.colors(checkedTrackColor = Ink.Amber, checkedThumbColor = Ink.OnAmber))
    }
    if (part != TypesPart.Widget) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { editing = Profiles.newProfile(private = false) }) { Text("+ New type") }
        TextButton(onClick = { editing = Profiles.newProfile(private = true) }) { Text("+ New private type") }
    }
    editing?.let { e -> ProfileEditor(e, canDelete = list.size > 1 && list.any { it.id == e.id }, onDone = {
        editing = null; Shortcuts.update(ctx); BrainWidget.refresh(ctx)
    }) }
}

/** Settings → Capture types: which type the big mic button (and Type, Paste, the tile) uses. */
@Composable
private fun DefaultTypePicker(list: List<CaptureProfile>, current: String, pick: (String) -> Unit) {
    val choices = list.filter { !it.private && it.name.isNotBlank() }
    val sel = choices.firstOrNull { it.id == current } ?: choices.firstOrNull() ?: return
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.Raised).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Default type", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink.Text)
            Text("Big mic button, Type and Paste", fontSize = 12.sp, color = Ink.TextDim)
        }
        Box {
            Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Ink.Amber).clickable { open = true }
                .heightIn(min = 40.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MIcon(painterResource(typeIcon(sel.icon)), null, Modifier.size(16.dp), tint = Ink.OnAmber)
                Text(sel.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.OnAmber)
                Text("▾", fontSize = 13.sp, color = Ink.OnAmber)
            }
            DropdownMenu(open, onDismissRequest = { open = false }, containerColor = Ink.Panel) {
                choices.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.name, fontWeight = if (p.id == sel.id) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (p.id == sel.id) Ink.Amber else Ink.Text) },
                        leadingIcon = { MIcon(painterResource(typeIcon(p.icon)), null, Modifier.size(18.dp),
                            tint = if (p.id == sel.id) Ink.Amber else Ink.TextStrong) },
                        onClick = { open = false; pick(p.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileEditor(start: CaptureProfile, canDelete: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var p by remember { mutableStateOf(start) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    // Back or Cancel with unsaved changes asks first; a tap outside can't close it (there is no outside).
    val leave = { if (p != start) confirmDiscard = true else onDone() }
    val save = {
        Profiles.put(ctx, p.copy(name = p.name.trim(), subtitle = p.subtitle.trim(),
            instruction = p.instruction.ifBlank { Profiles.IDEA_INSTRUCTION }))
        onDone()
    }
    Dialog(onDismissRequest = leave, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnClickOutside = false, decorFitsSystemWindows = false)) {
      Surface(Modifier.fillMaxSize(), color = Ink.Panel) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = leave) { Text("Cancel") }
                Text(if (start.name.isBlank()) "New ${if (start.private) "private " else ""}type" else "Edit ${start.name}",
                    Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink.Text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                TextButton(enabled = p.name.isNotBlank(), onClick = save) { Text("Save") }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(p.name, { p = p.copy(name = it.take(24)) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(p.subtitle, { p = p.copy(subtitle = it.take(40)) }, label = { Text("Short description") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Icon", fontSize = 13.sp, color = Ink.TextDim)
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Profiles.ICONS.forEach { name ->
                        Box(Modifier.size(36.dp).clip(CircleShape).background(if (p.icon == name) Ink.Amber else Ink.Raised)
                            .clickable { p = p.copy(icon = name) }, contentAlignment = Alignment.Center) {
                            MIcon(painterResource(typeIcon(name)), name, Modifier.size(18.dp), tint = if (p.icon == name) Ink.OnAmber else Ink.TextStrong)
                        }
                    }
                }
                OutlinedTextField(p.instruction, { p = p.copy(instruction = it) }, label = { Text("Instruction for the AI") },
                    minLines = 5, modifier = Modifier.fillMaxWidth())
                Text("The app adds the rules that keep every line linked to your exact words, and the title.",
                    fontSize = 12.sp, color = Ink.TextDim)
                Profiles.default(p.id)?.let { d ->
                    if (d.instruction != p.instruction) TextButton(onClick = { p = p.copy(instruction = d.instruction) }) { Text("Reset to the original instruction") }
                }
                // How the clean version is laid out.
                Text("Format", fontSize = 13.sp, color = Ink.TextDim)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Bullets" to (!p.checklist && !p.mixed), "Checklist" to p.checklist, "Text + lists" to p.mixed).forEach { (label, on) ->
                        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Ink.Amber else Ink.Raised)
                            .clickable { p = p.copy(checklist = label == "Checklist", mixed = label == "Text + lists") }
                            .heightIn(min = 40.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) Ink.OnAmber else Ink.TextSoft)
                        }
                    }
                }
                Text(when {
                    p.checklist -> "\"- [ ]\" tasks, with due dates when you say a day"
                    p.mixed -> "Short paragraphs for connected thoughts, bullets for lists"
                    else -> "One bullet per point"
                }, fontSize = 12.sp, color = Ink.TextDim)
                if (p.private) Text("Private: notes of this type stay inside the app and never go to the vault.", fontSize = 12.sp, color = Ink.Blue)
                if (p.name.isNotBlank()) TextButton(onClick = { Shortcuts.pin(ctx, p) }) { Text("Add a one-tap icon to the home screen") }
                if (canDelete) TextButton(onClick = { confirmDelete = true }) { Text("Delete this type", color = Ink.Error) }
            }
        }
      }
    }
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false }, containerColor = Ink.Panel,
        title = { Text("Discard your changes?") },
        text = { Text("What you changed in this type is not saved yet.") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onDone() }) { Text("Discard", color = Ink.Error) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false }, containerColor = Ink.Panel,
        title = { Text("Delete ${p.name}?") },
        text = { Text("Notes already saved keep their text. New notes can't use this type any more; its widget button and icon go away.") },
        confirmButton = { TextButton(onClick = { Profiles.delete(ctx, p.id); confirmDelete = false; onDone() }) { Text("Delete", color = Ink.Error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
    )
}

/** Home-screen shortcuts: one per type on long-press of the app icon, and pinned one-tap icons. */
object Shortcuts {
    fun update(ctx: Context) = runCatching {
        val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return@runCatching
        val list = Profiles.all(ctx).filter { it.name.isNotBlank() }
        val max = sm.maxShortcutCountPerActivity.coerceAtMost(4)
        sm.dynamicShortcuts = list.take(max).mapIndexed { i, p -> info(ctx, p).setRank(i).build() }
        // Keep pinned icons in step with renamed types; disable those whose type was deleted.
        val ids = list.map { "type-${it.id}" }.toSet()
        sm.updateShortcuts(list.map { info(ctx, it).build() }.filter { s -> sm.pinnedShortcuts.any { it.id == s.id } })
        val gone = sm.pinnedShortcuts.map { it.id }.filter { it.startsWith("type-") && it !in ids }
        if (gone.isNotEmpty()) sm.disableShortcuts(gone, "This capture type was deleted")
    }

    fun pin(ctx: Context, p: CaptureProfile) {
        val sm = ctx.getSystemService(ShortcutManager::class.java)
        if (sm?.isRequestPinShortcutSupported != true) {
            Toast.makeText(ctx, "Your home screen doesn't accept pinned icons", Toast.LENGTH_LONG).show(); return
        }
        sm.requestPinShortcut(info(ctx, p).build(), null)
    }

    private fun info(ctx: Context, p: CaptureProfile) = ShortcutInfo.Builder(ctx, "type-${p.id}")
        .setShortLabel(p.name.ifBlank { "Talk" }).setLongLabel("${p.name}: start talking")
        .setIcon(Icon.createWithAdaptiveBitmap(iconBitmap(ctx, p)))
        .setIntent(CaptureActivity.intent(ctx, p.id, start = true).setAction(android.content.Intent.ACTION_VIEW))

    /** Adaptive icon: the type's glyph on amber (private types on grey), like the widget buttons. */
    private fun iconBitmap(ctx: Context, p: CaptureProfile): Bitmap {
        val size = 216 // 108 dp at 2x; the launcher masks the outer part
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor((if (p.private) Ink.Raised else Ink.Amber).toArgb())
        val d = ContextCompat.getDrawable(ctx, typeIcon(p.icon))!!.mutate()
        d.colorFilter = PorterDuffColorFilter((if (p.private) Ink.TextStrong else Ink.OnAmber).toArgb(), PorterDuff.Mode.SRC_IN)
        val g = 72; val o = (size - g) / 2
        d.setBounds(o, o, o + g, o + g)
        d.draw(c)
        return bmp
    }
}
