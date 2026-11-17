package org.brain.capture.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
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
        synchronized(jobs) {
            jobs[c.id]?.cancel()
            jobs[c.id] = AppScope.launch {
                delay(UNDO_MS)
                synchronized(jobs) { jobs.remove(c.id) }
                runCatching { Notes.delete(app, c) }.onFailure {
                    withContext(Dispatchers.Main) { Toast.makeText(app, "Could not delete: ${it.message}", Toast.LENGTH_LONG).show() }
                }
                _hidden.value = _hidden.value - c.id
            }
        }
    }

    fun undo(id: String) {
        synchronized(jobs) { jobs.remove(id)?.cancel() }
        _hidden.value = _hidden.value - id
    }
}

/**
 * Wraps a card: dragging left reveals a red strip with a bin that grows (and the phone ticks) once
 * letting go would delete. Past the line, the card flies off; short of it, it springs back.
 */
@Composable
fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val delete by rememberUpdatedState(onDelete)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { if (it == SwipeToDismissBoxValue.EndToStart) { delete(); true } else false },
        positionalThreshold = { it * 0.4f },
    )
    val armed = state.targetValue == SwipeToDismissBoxValue.EndToStart
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(armed) { if (armed) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

    SwipeToDismissBox(state, enableDismissFromStartToEnd = false, backgroundContent = {
        val dragging = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
        val bg by animateColorAsState(when {
            armed -> MaterialTheme.colorScheme.error
            dragging -> MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
            else -> Color.Transparent
        }, label = "swipeBg")
        val scale by animateFloatAsState(if (armed) 1.25f else 0.9f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "swipeIcon")
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)).background(bg).padding(end = 28.dp),
            contentAlignment = Alignment.CenterEnd) {
            if (dragging) Icon(painterResource(R.drawable.ic_delete), "Delete", Modifier.size(24.dp).graphicsLayer { scaleX = scale; scaleY = scale },
                tint = if (armed) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.error)
        }
    }) { content() }
}
