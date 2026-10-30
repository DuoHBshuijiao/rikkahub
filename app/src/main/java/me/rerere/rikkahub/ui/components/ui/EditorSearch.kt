package me.rerere.rikkahub.ui.components.ui

import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/** Literal, non-overlapping matches. Offsets use the same UTF-16 indices as TextFieldState. */
internal fun findEditorMatches(
    text: String,
    query: String,
    checkCancelled: () -> Unit = {},
): List<TextRange> {
    if (query.isEmpty()) return emptyList()
    val matches = ArrayList<TextRange>()
    var offset = 0
    while (offset <= text.length - query.length) {
        checkCancelled()
        val start = text.indexOf(query, offset, ignoreCase = true)
        if (start < 0) break
        matches.add(TextRange(start, start + query.length))
        offset = start + query.length
    }
    return matches
}

/** First match intersecting or following an offset, in O(log n). */
internal fun List<TextRange>.firstEditorMatchAt(offset: Int): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (this[middle].end <= offset) low = middle + 1 else high = middle
    }
    return low
}

internal fun nextEditorMatch(current: Int, direction: Int, count: Int): Int =
    if (count == 0) -1 else Math.floorMod(current + direction, count)

internal data class EditorSearchResult(
    val text: String = "",
    val query: String = "",
    val matches: List<TextRange> = emptyList(),
)

internal suspend fun observeEditorSearch(
    editor: LongTextEditorState,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    onSearching: (Boolean) -> Unit = {},
) {
    snapshotFlow { editor.text.text to editor.query.text }.collectLatest { (text, query) ->
        if (query.isEmpty()) {
            editor.result = EditorSearchResult()
            editor.currentMatch = -1
            editor.navigation = null
            editor.searchPending = false
            return@collectLatest
        }
        onSearching(true)
        try {
            editor.searchPending = true
            editor.navigation = null
            delay(150)
            val result = withContext(dispatcher) {
                val context = currentCoroutineContext()
                val document = text.toString()
                val term = query.toString()
                EditorSearchResult(document, term, findEditorMatches(document, term) { context.ensureActive() })
            }
            if (editor.text.text != text || editor.query.text != query) return@collectLatest
            val queryChanged = editor.result.query != result.query
            val previousOffset = editor.result.matches.getOrNull(editor.currentMatch)?.start
                ?: editor.restoredMatchOffset ?: editor.text.selection.min
            val offset = if (queryChanged &&
                editor.restoredMatchOffset == null) {
                editor.text.selection.min
            } else {
                previousOffset
            }
            val candidate = result.matches.firstEditorMatchAt(offset)
            editor.result = result
            editor.currentMatch = when {
                result.matches.isEmpty() -> -1
                candidate < result.matches.size -> candidate
                else -> 0
            }
            editor.restoredMatchOffset = null
            editor.searchPending = false
            if (queryChanged) editor.navigation = result.matches.getOrNull(editor.currentMatch)
        } finally {
            onSearching(false)
        }
    }
}
