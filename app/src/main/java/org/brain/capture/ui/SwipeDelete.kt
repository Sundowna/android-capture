package org.brain.capture.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.brain.capture.R
import org.brain.capture.data.Capture
import org.brain.capture.pipeline.Notes
import org.brain.capture.pipeline.Undo
import org.brain.capture.share.AppScope

/**
 * Swipe a card left to delete it. The card leaves at once and an Undo bar shows; the note itself
 * is deleted (moved to the vault's .trash, private ones removed) only when the Undo time is up.
 * Runs in the app scope, so leaving the screen does not lose a delete.
 */
object SwipeDeletes {
    const val UNDO_MS = 5000L
    private val _hidden = MutableStateFlow<Set<String>>(emptySet())
    /** Cards swiped away and waiting for their Undo time to pass. */
    val hidden: StateFlow<Set<String>> = _hidden
    private val jobs = HashMap<String, Job>()

    fun start(ctx: Context, c: Capture) {
        val app = ctx.applicationContext
        _hidden.value = _hidden.value + c.id
        Undo.record(Undo.Action.PendingDelete(c.id))
        synchronized(jobs) {
            jobs[c.id]?.cancel()
            jobs[c.id] = AppScope.launch {
                delay(UNDO_MS)
                synchronized(jobs) { jobs.remove(c.id) }
                // Still the last action: the real delete takes over the home screen's Undo.
                runCatching { Notes.delete(app, c, undoable = Undo.isPending(c.id)) }.onFailure {
                    withContext(Dispatchers.Main) { Toast.makeText(app, "Could not delete: ${it.message}", Toast.LENGTH_LONG).show() }
                }
                _hidden.value = _hidden.value - c.id
            }
        }
    }

    fun undo(id: String) {
        synchronized(jobs) { jobs.remove(id)?.cancel() }
        _hidden.value = _hidden.value - id
        Undo.forget(id)
    }
}

/**
 * Wraps a card: dragging left reveals a red strip with a bin. The card lags behind the finger
 * (sticky) until it passes the delete line at [LINE] of its width; there the bin grows, the strip
 * turns red and the phone ticks. Letting go past the line deletes; short of it, the card springs
 * back. A fast flick does not count: only the distance does, so a quick thumb arc can't delete.
 */
@Composable
fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val delete by rememberUpdatedState(onDelete)
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var width by remember { mutableFloatStateOf(1f) }
    var raw by remember { mutableFloatStateOf(0f) }
    val line = width * LINE
    val armed = -offset.value >= line
    LaunchedEffect(armed) { if (armed) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

    // Before the line the card moves at STICKY of the finger's speed, after it 1:1.
    fun visible(r: Float): Float {
        val d = (-r).coerceAtLeast(0f)
        val l = width * LINE // read here: the gesture keeps the first composition's lambdas
        val lineRaw = l / STICKY
        return -(if (d <= lineRaw) d * STICKY else l + (d - lineRaw))
    }

    Box(Modifier.onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { // pick the card up where it is, even mid spring-back
                    val v = -offset.value; val l = width * LINE
                    raw = -(if (v <= l) v / STICKY else l / STICKY + (v - l))
                },
                onHorizontalDrag = { change, dx ->
                    change.consume()
                    raw = (raw + dx).coerceAtMost(0f)
                    scope.launch { offset.snapTo(visible(raw)) }
                },
                onDragEnd = {
                    scope.launch {
                        if (-offset.value >= width * LINE) {
                            offset.animateTo(-width, tween(180))
                            delete()
                        } else offset.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 600f))
                    }
                },
                onDragCancel = { scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 600f)) } },
            )
        }) {
        val dragging = offset.value < -1f
        val bg by animateColorAsState(when {
            armed -> MaterialTheme.colorScheme.error
            dragging -> MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
            else -> Color.Transparent
        }, label = "swipeBg")
        val scale by animateFloatAsState(if (armed) 1.25f else 0.9f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "swipeIcon")
        Box(Modifier.matchParentSize().clip(RoundedCornerShape(18.dp)).background(bg).padding(end = 28.dp),
            contentAlignment = Alignment.CenterEnd) {
            if (dragging) Icon(painterResource(R.drawable.ic_delete), "Delete", Modifier.size(24.dp).graphicsLayer { scaleX = scale; scaleY = scale },
                tint = if (armed) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.error)
        }
        Box(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }) { content() }
    }
}

/** Share of the card's width the card itself must travel before letting go deletes. */
private const val LINE = 0.45f
/** How far the card moves per finger movement before the line (the stickiness). */
private const val STICKY = 0.6f
