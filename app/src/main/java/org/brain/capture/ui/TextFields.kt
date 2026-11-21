package org.brain.capture.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * A text field inside a scrolling area that keeps the cursor in sight: after every change (typing,
 * Enter, paste) the cursor's line, with a little room above and below, is scrolled into view.
 * Compose's own field only does this when it gets focus, not while typing in a long note.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CursorFollowingTextField(
    value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, textStyle: TextStyle,
) {
    val requester = remember { BringIntoViewRequester() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var focused by remember { mutableStateOf(false) }
    val margin = with(LocalDensity.current) { 28.dp.toPx() }
    // A new layout comes with every text change; the selection covers moving the cursor.
    LaunchedEffect(layout, value.selection, focused) {
        val l = layout ?: return@LaunchedEffect
        if (!focused) return@LaunchedEffect
        val r = l.getCursorRect(value.selection.end.coerceIn(0, l.layoutInput.text.length))
        requester.bringIntoView(Rect(r.left, r.top - margin, r.right, r.bottom + margin))
    }
    BasicTextField(value, onValueChange,
        // After the caller's padding, so the cursor rectangle and this area line up.
        modifier.bringIntoViewRequester(requester).onFocusChanged { focused = it.isFocused },
        enabled = enabled, textStyle = textStyle, cursorBrush = SolidColor(Ink.Amber),
        onTextLayout = { layout = it })
}

/**
 * A very fine scroll indicator along the right edge, shown only when there is more to scroll.
 * Put it before `verticalScroll(state)` in the modifier chain, so it draws over the visible area.
 */
internal fun Modifier.thinScrollbar(state: ScrollState, color: Color = Ink.Bar): Modifier = drawWithContent {
    drawContent()
    val max = state.maxValue
    if (max <= 0 || max == Int.MAX_VALUE) return@drawWithContent
    val viewport = size.height
    val width = 3.dp.toPx()
    val thumb = (viewport * viewport / (viewport + max)).coerceAtLeast(28.dp.toPx())
    val top = (viewport - thumb) * state.value / max
    drawRoundRect(color.copy(alpha = 0.7f), topLeft = Offset(size.width - width, top), size = Size(width, thumb),
        cornerRadius = CornerRadius(width / 2))
}
