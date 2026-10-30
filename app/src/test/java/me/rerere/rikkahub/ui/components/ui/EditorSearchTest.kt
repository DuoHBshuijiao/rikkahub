package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.insert
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorSearchTest {
    @Test
    fun `literal search uses original unicode offsets and ignores case`() {
        val text = "🙂关键字 AbC\n关键字 abc"
        assertEquals(listOf(TextRange(2, 5), TextRange(10, 13)), findEditorMatches(text, "关键字"))
        assertEquals(listOf(TextRange(6, 9), TextRange(14, 17)), findEditorMatches(text, "ABC"))
        assertEquals(listOf(TextRange(0, 2)), findEditorMatches(text, "🙂"))
        assertEquals(listOf(TextRange(6, 13)), findEditorMatches(text, "abc\n关键字"))
        assertEquals(listOf(TextRange(0, 3)), findEditorMatches("a.b aXb", "a.b"))
    }

    @Test
    fun `empty missing and overlapping patterns have predictable results`() {
        assertEquals(emptyList<TextRange>(), findEditorMatches("abc", ""))
        assertEquals(emptyList<TextRange>(), findEditorMatches("", "a"))
        assertEquals(emptyList<TextRange>(), findEditorMatches("abc", "abcdef"))
        assertEquals(listOf(TextRange(0, 2), TextRange(2, 4)), findEditorMatches("aaaaa", "aa"))
    }

    @Test
    fun `navigation wraps and viewport lookup includes a match spanning the viewport`() {
        val matches = listOf(TextRange(0, 100), TextRange(105, 110), TextRange(200, 210))
        assertEquals(0, matches.firstEditorMatchAt(50))
        assertEquals(1, matches.firstEditorMatchAt(100))
        assertEquals(3, matches.firstEditorMatchAt(210))
        assertEquals(2, nextEditorMatch(0, -1, 3))
        assertEquals(0, nextEditorMatch(2, 1, 3))
        assertEquals(-1, nextEditorMatch(-1, 1, 0))
    }

    @Test(expected = CancellationException::class)
    fun `dense search can be cancelled`() {
        var checks = 0
        findEditorMatches("字".repeat(50_000), "字") {
            if (++checks == 10) throw CancellationException()
        }
    }

    @Test
    fun `rapid queries publish only the latest result`() = runTest {
        val editor = LongTextEditorState(TextFieldState("alpha beta beta"))
        val job = backgroundScope.launch { observeEditorSearch(editor, StandardTestDispatcher(testScheduler)) }
        runCurrent()
        editor.query.setTextAndPlaceCursorAtEnd("alpha")
        Snapshot.sendApplyNotifications()
        runCurrent()
        advanceTimeBy(100)
        editor.query.setTextAndPlaceCursorAtEnd("beta")
        Snapshot.sendApplyNotifications()
        runCurrent()
        advanceTimeBy(149)
        runCurrent()
        assertTrue(editor.searchPending)
        assertTrue(editor.result.matches.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertFalse(editor.searchPending)
        assertEquals("beta", editor.result.query)
        assertEquals(listOf(TextRange(6, 10), TextRange(11, 15)), editor.result.matches)
        job.cancel()
    }

    @Test
    fun `selection changes reuse results and editing never steals the caret`() = runTest {
        val editor = LongTextEditorState(TextFieldState("cat cat"))
        editor.query.setTextAndPlaceCursorAtEnd("cat")
        val job = backgroundScope.launch { observeEditorSearch(editor, StandardTestDispatcher(testScheduler)) }
        runCurrent()
        advanceTimeBy(150)
        runCurrent()
        val firstResult = editor.result
        editor.navigation = null

        editor.text.edit { selection = TextRange(2) }
        Snapshot.sendApplyNotifications()
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        assertSame(firstResult, editor.result)
        assertNull(editor.navigation)

        editor.text.edit {
            insert(0, "big ")
            selection = TextRange(4)
        }
        Snapshot.sendApplyNotifications()
        runCurrent()
        advanceTimeBy(150)
        runCurrent()
        assertEquals(listOf(TextRange(4, 7), TextRange(8, 11)), editor.result.matches)
        assertEquals(TextRange(4), editor.text.selection)
        assertNull(editor.navigation)
        job.cancel()
    }

    @Test
    fun `closing search selects a valid match but never changes text or readonly selection`() {
        val text = TextFieldState("one two")
        val editor = LongTextEditorState(text)
        editor.query.setTextAndPlaceCursorAtEnd("two")
        editor.result = EditorSearchResult("one two", "two", listOf(TextRange(4, 7)))
        editor.currentMatch = 0
        editor.closeSearch(readOnly = true)
        assertEquals(TextRange(7), text.selection)
        editor.closeSearch(readOnly = false)
        assertEquals(TextRange(4, 7), text.selection)
        assertEquals("one two", text.text.toString())

        text.setTextAndPlaceCursorAtEnd("short")
        editor.closeSearch(readOnly = false)
        assertEquals(TextRange(5), text.selection)
        assertEquals("short", text.text.toString())
    }

    @Test
    fun `changing the query cannot navigate or select results from the previous query`() {
        val editor = LongTextEditorState(TextFieldState("one two"))
        editor.query.setTextAndPlaceCursorAtEnd("two")
        editor.result = EditorSearchResult("one two", "two", listOf(TextRange(4, 7)))
        editor.currentMatch = 0
        // The event may arrive before the snapshot collector has set searchPending.
        editor.query.setTextAndPlaceCursorAtEnd("one")
        editor.navigate(1)
        assertNull(editor.navigation)
        editor.closeSearch(readOnly = false)
        assertEquals(TextRange(7), editor.text.selection)
    }

    @Test
    fun `clearing the query immediately removes matches without copying the draft`() = runTest {
        val editor = LongTextEditorState(TextFieldState("字".repeat(50_000)))
        editor.query.setTextAndPlaceCursorAtEnd("字")
        val job = backgroundScope.launch { observeEditorSearch(editor, StandardTestDispatcher(testScheduler)) }
        runCurrent()
        advanceTimeBy(150)
        runCurrent()
        assertEquals(50_000, editor.result.matches.size)
        editor.query.setTextAndPlaceCursorAtEnd("")
        Snapshot.sendApplyNotifications()
        runCurrent()
        assertTrue(editor.result.matches.isEmpty())
        assertEquals("", editor.result.text)
        assertFalse(editor.searchPending)
        job.cancel()
    }
}
