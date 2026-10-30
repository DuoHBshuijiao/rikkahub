package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange

internal data class EditorAnchor(val offset: Int, val lineFraction: Float)

internal class EditorViewport {
    val scroll = ScrollState(0)
    var layout by mutableStateOf<TextLayoutResult?>(null)
    var pendingAnchor by mutableStateOf<EditorAnchor?>(null)

    // Measurement is consumed only by event handlers or after a mode change, not observed by layout.
    var measuredHeight = 0

    fun anchor(): EditorAnchor? {
        val result = layout ?: return null
        val line = result.getLineForVerticalPosition(scroll.value.toFloat())
        val top = result.getLineTop(line)
        val height = (result.getLineBottom(line) - top).coerceAtLeast(1f)
        return EditorAnchor(result.getLineStart(line), (scroll.value - top) / height)
    }

    fun scrollOffset(anchor: EditorAnchor): Int? {
        val result = layout ?: return null
        val line = result.getLineForOffset(anchor.offset.coerceIn(0, result.layoutInput.text.length))
        val top = result.getLineTop(line)
        return (top + anchor.lineFraction * (result.getLineBottom(line) - top)).toInt()
    }
}

internal class LongTextEditorState(val text: TextFieldState) {
    val inline = EditorViewport()
    val fullscreen = EditorViewport()
    val query = TextFieldState()
    var isFullscreen by mutableStateOf(false)
        private set
    var searchVisible by mutableStateOf(false)
        private set
    var searchPending by mutableStateOf(false)
    var result by mutableStateOf(EditorSearchResult())
    var currentMatch by mutableIntStateOf(-1)
    var navigation by mutableStateOf<TextRange?>(null)
    var restoredMatchOffset: Int? = null
    var focusEditorRequest by mutableIntStateOf(0)
        private set

    fun open(search: Boolean = false) {
        fullscreen.pendingAnchor = inline.anchor()
        inline.layout = null
        isFullscreen = true
        searchVisible = search
    }

    fun close() {
        inline.pendingAnchor = fullscreen.anchor()
        fullscreen.layout = null
        isFullscreen = false
        searchVisible = false
    }

    fun showSearch() {
        searchVisible = true
    }

    fun closeSearch(readOnly: Boolean) {
        if (!readOnly && !searchPending) {
            result.matches.getOrNull(currentMatch)?.let { match ->
                // Never apply an offset calculated for an earlier draft.
                if (hasCurrentSearchResult()) {
                    text.edit { selection = match }
                }
            }
        }
        searchVisible = false
        if (!readOnly) focusEditorRequest++
    }

    fun navigate(direction: Int) {
        if (searchPending || !hasCurrentSearchResult()) return
        currentMatch = nextEditorMatch(currentMatch, direction, result.matches.size)
        navigation = result.matches.getOrNull(currentMatch)
    }

    private fun hasCurrentSearchResult(): Boolean =
        text.text.contentEquals(result.text) && query.text.contentEquals(result.query)

    companion object {
        fun saver(text: TextFieldState) = listSaver<LongTextEditorState, Any>(
            save = { state ->
                val viewport = if (state.isFullscreen) state.fullscreen else state.inline
                val anchor = viewport.pendingAnchor ?: viewport.anchor()
                listOf(
                    state.isFullscreen,
                    state.searchVisible,
                    state.query.text.toString(),
                    state.result.matches.getOrNull(state.currentMatch)?.start ?: -1,
                    anchor?.offset ?: 0,
                    anchor?.lineFraction ?: 0f,
                    state.inline.measuredHeight,
                )
            },
            restore = { saved ->
                LongTextEditorState(text).apply {
                    isFullscreen = saved[0] as Boolean
                    searchVisible = saved[1] as Boolean
                    query.edit { append(saved[2] as String) }
                    val matchOffset = saved[3] as Int
                    if (matchOffset >= 0) restoredMatchOffset = matchOffset
                    val anchor = EditorAnchor(saved[4] as Int, saved[5] as Float)
                    if (isFullscreen) fullscreen.pendingAnchor = anchor else inline.pendingAnchor = anchor
                    inline.measuredHeight = saved[6] as Int
                }
            },
        )
    }
}

@Composable
internal fun rememberLongTextEditorState(
    text: TextFieldState,
    sessionKey: Any? = text,
): LongTextEditorState = rememberSaveable(text, sessionKey, saver = LongTextEditorState.saver(text)) {
    LongTextEditorState(text)
}
