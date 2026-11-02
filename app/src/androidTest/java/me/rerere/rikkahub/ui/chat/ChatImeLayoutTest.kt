package me.rerere.rikkahub.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.ui.EditorTextField
import me.rerere.rikkahub.ui.components.ui.rememberLongTextEditorState
import me.rerere.rikkahub.ui.hooks.ImeLazyListAutoScroller
import me.rerere.rikkahub.ui.pages.chat.rememberChatListContentPadding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatImeLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val ime = TestImeInsets()
    private var editing by mutableStateOf(true)
    private lateinit var list: LazyListState
    private lateinit var draft: TextFieldState
    private var compositions = 0
    private var itemHeightPx = 0

    @Test
    fun editingImeAnimationKeepsReadingPositionAndSkipsComposition() {
        setContent()
        val initialPosition = position()
        val initialSelection = compose.runOnIdle { draft.selection }
        val initialCompositions = compose.runOnIdle { compositions }
        val initialText = compose.runOnIdle { draft.text.toString() }

        // Include a final zero frame, a taller replacement IME, and a second open/close cycle.
        for (height in listOf(60, 120, 240, 380, 240, 60, 0, 80, 300, 460, 180, 0)) {
            setImeHeight(height)
            assertEquals(initialPosition, position())
        }
        compose.runOnIdle {
            assertEquals(initialCompositions, compositions)
            assertEquals(initialText, draft.text.toString())
            assertEquals(initialSelection, draft.selection)
        }
        compose.onNodeWithTag("ime-test-history").performTouchInput {
            swipeUp(startY = height * 0.35f, endY = height * 0.1f)
        }
        assertTrue(position() > initialPosition)
        val userPosition = position()
        setImeHeight(300)
        assertEquals(userPosition, position())
    }

    @Test
    fun editingWithAnAlreadyVisibleImeAndNoNavigationInsetKeepsPosition() {
        ime.bottom = 300
        setContent(navigationBottom = 0)
        val initialPosition = position()
        for (height in listOf(460, 180, 0, 300)) {
            setImeHeight(height)
            assertEquals(initialPosition, position())
        }
    }

    @Test
    fun draftFollowingHandlesZeroAndReenablingWithoutCatchingUp() {
        editing = false
        setContent()
        val initialPosition = position()
        repeat(2) {
            setImeHeight(300)
            assertEquals(initialPosition + 300, position())
            setImeHeight(0)
            assertEquals(initialPosition, position())
        }

        setImeHeight(300)
        compose.runOnIdle { editing = true }
        val editingPosition = position()
        setImeHeight(400)
        assertEquals(editingPosition, position())
        compose.runOnIdle { editing = false }
        assertEquals(editingPosition, position())
        setImeHeight(450)
        assertEquals(editingPosition + 50, position())
        setImeHeight(0)
        assertEquals(editingPosition - 400, position())
    }

    private fun setContent(navigationBottom: Int = 24) {
        compose.setContent {
            itemHeightPx = with(LocalDensity.current) { 48.dp.roundToPx() }
            list = rememberLazyListState(initialFirstVisibleItemIndex = 30)
            draft = rememberTextFieldState("用于验证输入法动画的长消息。".repeat(4_000))
            val editor = rememberLongTextEditorState(draft)
            val navigation = WindowInsets(0, 0, 0, navigationBottom)
            MaterialTheme {
                Scaffold(
                    bottomBar = {
                        Box(Modifier.windowInsetsPadding(ime).windowInsetsPadding(navigation)) {
                            EditorTextField(
                                state = draft,
                                viewport = editor.inline,
                                modifier = Modifier.fillMaxWidth(),
                                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
                            )
                        }
                    },
                ) { padding ->
                    val contentPadding = rememberChatListContentPadding(padding, editing, ime, navigation)
                    SideEffect { compositions++ }
                    ImeLazyListAutoScroller(list, enabled = !editing, ime = ime)
                    LazyColumn(
                        state = list,
                        contentPadding = contentPadding,
                        modifier = Modifier.fillMaxSize().testTag("ime-test-history"),
                    ) {
                        items(100, key = { it }, contentType = { "row" }) { index ->
                            Text("消息 $index", Modifier.height(48.dp))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertTrue(itemHeightPx > 0)
    }

    private fun setImeHeight(height: Int) {
        compose.runOnIdle { ime.bottom = height }
        compose.waitForIdle()
    }

    private fun position(): Int = compose.runOnIdle {
        list.firstVisibleItemIndex * itemHeightPx + list.firstVisibleItemScrollOffset
    }

    private class TestImeInsets : WindowInsets {
        var bottom by mutableIntStateOf(0)
        override fun getLeft(density: Density, layoutDirection: LayoutDirection) = 0
        override fun getTop(density: Density) = 0
        override fun getRight(density: Density, layoutDirection: LayoutDirection) = 0
        override fun getBottom(density: Density) = bottom
    }
}
