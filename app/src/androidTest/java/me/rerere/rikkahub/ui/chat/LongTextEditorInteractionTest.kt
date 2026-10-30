package me.rerere.rikkahub.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import me.rerere.rikkahub.ui.components.ui.EditorPlaceholder
import me.rerere.rikkahub.ui.components.ui.EditorTextField
import me.rerere.rikkahub.ui.components.ui.FullscreenTextEditor
import me.rerere.rikkahub.ui.components.ui.LongTextEditorState
import me.rerere.rikkahub.ui.components.ui.TextArea
import me.rerere.rikkahub.ui.components.ui.rememberLongTextEditorState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import me.rerere.ui.toast.rememberToasterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LongTextEditorInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun searchNavigationKeepsQueryFocusAndClosingSearchSelectsTheMatch() {
        lateinit var draft: TextFieldState
        val content = "开头关键词\n" + "正文\n".repeat(2000) + "末尾关键词"
        compose.setContent {
            draft = rememberTextFieldState(content)
            CompositionLocalProvider(LocalToaster provides rememberToasterState()) {
                RikkahubTheme { TextArea(draft, label = "正文") }
            }
        }
        compose.onNodeWithContentDescription("查找").performClick()
        compose.onNodeWithTag("text-area-inline").assertDoesNotExist()
        compose.onNodeWithTag("editor-search-query").performTextReplacement("关键词")
        awaitText("1 / 2")
        compose.onNodeWithContentDescription("下一处匹配").performClick()
        compose.onNodeWithTag("editor-search-count").assertTextContains("2 / 2")
        compose.onNodeWithTag("editor-search-query").assertIsFocused()
        compose.onNodeWithContentDescription("关闭查找").performClick()
        compose.runOnIdle {
            assertEquals(content, draft.text.toString())
            assertEquals(content.lastIndexOf("关键词"), draft.selection.start)
            assertEquals(content.length, draft.selection.end)
        }
        compose.onNodeWithTag("text-area-fullscreen").assertIsFocused()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithTag("text-area-inline").assertIsDisplayed().assertTextContains(content)
    }

    @Test
    fun searchAndDraftSurviveRestorationAndQuickScrollDoesNotMoveSelection() {
        lateinit var draft: TextFieldState
        val content = "目标\n" + "长文本草稿\n".repeat(2000) + "目标"
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            draft = rememberTextFieldState(content)
            CompositionLocalProvider(LocalToaster provides rememberToasterState()) {
                RikkahubTheme { TextArea(draft, label = "提示词") }
            }
        }
        compose.onNodeWithContentDescription("查找").performClick()
        compose.onNodeWithTag("editor-search-query").performTextReplacement("目标")
        awaitText("1 / 2")
        compose.onNodeWithContentDescription("下一处匹配").performClick()
        restoration.emulateSavedInstanceStateRestore()
        awaitText("2 / 2")
        compose.onNodeWithTag("editor-search-query").assertTextContains("目标")
        val selection = compose.runOnIdle { draft.selection }
        compose.onNodeWithTag("editor-scrollbar").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.runOnIdle {
            assertEquals(selection, draft.selection)
            assertEquals(content, draft.text.toString())
        }
    }

    @Test
    fun readonlySearchLocatesWithoutSelectingOrEditing() {
        lateinit var draft: TextFieldState
        compose.setContent {
            draft = rememberTextFieldState("开头\n".repeat(1000) + "目标")
            CompositionLocalProvider(LocalToaster provides rememberToasterState()) {
                RikkahubTheme { TextArea(draft, label = "只读", readOnly = true) }
            }
        }
        val selection = compose.runOnIdle { draft.selection }
        compose.onNodeWithContentDescription("查找").performClick()
        compose.onNodeWithTag("editor-search-query").performTextReplacement("目标")
        awaitText("1 / 1")
        compose.onNodeWithContentDescription("关闭查找").performClick()
        compose.runOnIdle { assertEquals(selection, draft.selection) }
    }

    @Test
    fun fullscreenRoundTripRestoresTheReadingAnchorAwayFromTheCaret() {
        lateinit var editor: LongTextEditorState
        compose.setContent {
            editor = rememberLongTextEditorState(rememberTextFieldState("用于测试不同宽度换行的长文本。\n".repeat(2000)))
            RikkahubTheme {
                Column {
                    TextButton(onClick = { editor.open() }) { Text("打开编辑器") }
                    if (editor.isFullscreen) {
                        EditorPlaceholder(editor.inline)
                    } else {
                        EditorTextField(
                            state = editor.text,
                            viewport = editor.inline,
                            modifier = Modifier.fillMaxWidth(),
                            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
                        )
                    }
                }
                if (editor.isFullscreen) FullscreenTextEditor(editor, "正文")
            }
        }
        val anchor = compose.runOnIdle {
            editor.inline.scroll.dispatchRawDelta(1500f)
            checkNotNull(editor.inline.anchor())
        }
        assertTrue(anchor.offset > 0)
        val selection = compose.runOnIdle { editor.text.selection }
        compose.onNodeWithText("打开编辑器").performClick()
        compose.waitUntil(5_000) { editor.fullscreen.pendingAnchor == null && editor.fullscreen.layout != null }
        compose.runOnIdle {
            assertEquals(editor.fullscreen.scrollOffset(anchor), editor.fullscreen.scroll.value)
            assertEquals(selection, editor.text.selection)
        }
        val fullscreenAnchor = compose.runOnIdle { checkNotNull(editor.fullscreen.anchor()) }
        compose.onNodeWithText("完成").performClick()
        compose.waitUntil(5_000) { editor.inline.pendingAnchor == null && editor.inline.layout != null }
        compose.runOnIdle {
            val expected = checkNotNull(editor.inline.scrollOffset(fullscreenAnchor))
            assertTrue(kotlin.math.abs(expected - editor.inline.scroll.value) <= 1)
            assertEquals(selection, editor.text.selection)
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("editor-search-count").assertTextContains(text) }.isSuccess
        }
    }
}
