package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Keep Scaffold's animated padding reads in lazy measurement, rather than composition. */
@Composable
internal fun rememberChatListContentPadding(
    scaffoldPadding: PaddingValues,
    isEditingMessage: Boolean,
    ime: WindowInsets = WindowInsets.ime,
    navigationBars: WindowInsets = WindowInsets.navigationBars,
): PaddingValues {
    val density = LocalDensity.current
    return remember(scaffoldPadding, isEditingMessage, ime, navigationBars, density) {
        ChatListContentPadding(scaffoldPadding) {
            if (isEditingMessage) {
                // ChatInput applies imePadding followed by navigationBarsPadding: their overlap
                // is consumed, so subtract only the IME space beyond the navigation bar.
                with(density) {
                    (ime.getBottom(this) - navigationBars.getBottom(this)).coerceAtLeast(0).toDp()
                }
            } else {
                0.dp
            }
        }
    }
}

private class ChatListContentPadding(
    private val scaffoldPadding: PaddingValues,
    private val extraImePadding: () -> Dp,
) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection) = 16.dp
    override fun calculateRightPadding(layoutDirection: LayoutDirection) = 16.dp
    override fun calculateTopPadding() = 16.dp + scaffoldPadding.calculateTopPadding()
    override fun calculateBottomPadding() =
        48.dp + (scaffoldPadding.calculateBottomPadding() - extraImePadding()).coerceAtLeast(0.dp)
}
