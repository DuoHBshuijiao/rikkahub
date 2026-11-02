package me.rerere.rikkahub.ui.hooks

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity

@Composable
fun ImeLazyListAutoScroller(
    lazyListState: LazyListState,
    enabled: Boolean = true,
    ime: WindowInsets = WindowInsets.ime,
) {
    if (!enabled) return
    val density = LocalDensity.current
    LaunchedEffect(lazyListState, ime, density) {
        // Starting (or re-enabling) with an already visible IME must not catch up its full height.
        var previousHeight = ime.getBottom(density)
        snapshotFlow { ime.getBottom(density) }.collect { keyboardHeight ->
            val delta = keyboardHeight - previousHeight
            previousHeight = keyboardHeight // Include the final zero frame on dismissal.
            if (delta != 0 && !lazyListState.isScrollInProgress) {
                lazyListState.scrollBy(delta.toFloat())
            }
        }
    }
}
