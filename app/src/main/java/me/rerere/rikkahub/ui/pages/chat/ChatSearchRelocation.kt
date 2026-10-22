package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.SearchJumpTarget
import kotlin.math.abs

internal class ChatSearchGeometry {
    var viewport by mutableStateOf<Rect?>(null)
    var match by mutableStateOf<Rect?>(null)
}

/** Coarse composition of the message, then relocation using the actual Text layout, never a ratio. */
@Composable
internal fun rememberChatSearchGeometry(
    state: LazyListState,
    conversation: Conversation,
    target: SearchJumpTarget?,
    topInset: Float,
    bottomInset: Float,
    onError: () -> Unit,
): ChatSearchGeometry {
    val geometry = remember(target?.requestId) { ChatSearchGeometry() }
    val currentOnError by rememberUpdatedState(onError)
    LaunchedEffect(target?.requestId) {
        if (target == null) return@LaunchedEffect
        val index = conversation.messageNodes.indexOfFirst { it.id.toString() == target.nodeId }
        if (index < 0) {
            currentOnError()
            return@LaunchedEffect
        }
        state.scrollToItem(index)
        val located = withTimeoutOrNull(5_000) {
            snapshotFlow { if (geometry.viewport != null) geometry.match else null }.filterNotNull().first()
        }
        if (located == null) {
            currentOnError()
            return@LaunchedEffect
        }
        // Layout can settle over several frames after expanding <details> or a code block.
        // Bound correction, and stop as soon as the list cannot scroll further.
        var stableFrames = 0
        repeat(24) {
            withFrameNanos { }
            val viewport = geometry.viewport ?: return@repeat
            val match = geometry.match ?: return@repeat
            val visibleTop = viewport.top + topInset
            val visibleBottom = viewport.bottom - bottomInset
            val desiredTop = visibleTop + ((visibleBottom - visibleTop - match.height) * 0.25f).coerceAtLeast(0f)
            val delta = match.top - desiredTop
            if (abs(delta) < 2f) {
                stableFrames++
                if (stableFrames >= 2) return@LaunchedEffect
            } else {
                stableFrames = 0
                val consumed = state.scrollBy(delta)
                if (abs(consumed) < 1f && match.top >= visibleTop &&
                    match.bottom <= visibleBottom) {
                    return@LaunchedEffect
                }
            }
        }
    }
    return geometry
}
