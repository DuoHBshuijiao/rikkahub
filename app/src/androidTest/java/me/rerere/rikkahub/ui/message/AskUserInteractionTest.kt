package me.rerere.rikkahub.ui.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import me.rerere.ai.interaction.AskUserProtocol
import me.rerere.ai.interaction.QuestionSelection
import me.rerere.ai.interaction.UserQuestion
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.components.message.AskUserQuestionnaire
import me.rerere.rikkahub.ui.components.message.AskUserToolStep
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AskUserInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun skippedQuestionReplacesTheFormWithItsCancellationReason() {
        var tool by mutableStateOf(UIMessagePart.Tool(
            toolCallId = "ask",
            toolName = "ask_user",
            input = """{"questions":[{"id":"q","question":"希望如何继续？","options":["先整理需求，再给出实现方案","直接开始实现并验证结果"],"selection_type":"single"}]}""",
            approvalState = ToolApprovalState.Pending,
        ))
        compose.setContent {
            RikkahubTheme(colorMode = ColorMode.DARK) {
                Column(Modifier.width(360.dp)) {
                    ChainOfThought(steps = listOf(tool)) {
                        AskUserToolStep(it, loading = false, onToolAnswer = { _, _ -> }, onToolApproval = { _, _, _ -> })
                    }
                }
            }
        }
        compose.onNodeWithTag("ask-user-submit").assertIsNotEnabled()
        compose.runOnIdle {
            tool = tool.copy(
                approvalState = ToolApprovalState.Denied("已跳过本次提问"),
                output = listOf(UIMessagePart.Text("cancelled")),
            )
        }
        compose.onNodeWithText("已跳过本次提问").assertExists()
        compose.onNodeWithTag("ask-user-submit").assertDoesNotExist()
    }

    @Test
    fun textRequiresDoubleTapWhileTheSelectionControlNeverStartsEditing() {
        val question = UserQuestion("q", "语言？", QuestionSelection.SINGLE, listOf("Kotlin", "Java"))
        var draft by mutableStateOf(AskUserProtocol.newDraft(listOf(question)))
        compose.setContent { RikkahubTheme { AskUserQuestionnaire(listOf(question), draft, { draft = it }) } }

        compose.onNodeWithTag("ask-text-q:s0").performClick()
        compose.mainClock.advanceTimeBy(400) // combinedClickable waits for a possible second tap.
        compose.runOnIdle { assertEquals(setOf("q:s0"), draft.questions.single().selectedIds) }
        compose.onNodeWithTag("ask-input-q:s0").assertDoesNotExist()

        compose.onNodeWithTag("ask-select-q:s1").performTouchInput { doubleClick() }
        compose.onNodeWithTag("ask-input-q:s1").assertDoesNotExist()
        compose.onNodeWithTag("ask-select-q:s1").assertIsSelected()

        compose.onNodeWithTag("ask-text-q:s0").performTouchInput { doubleClick() }
        compose.onNodeWithTag("ask-input-q:s0").assertExists().performTextInput(" / Compose")
        compose.runOnIdle { assertEquals(setOf("q:s0"), draft.questions.single().selectedIds) }
    }

    @Test
    fun emptyCustomInputNeedsOneTapAndTypingAddsItToMultiSelection() {
        val question = UserQuestion("q", "平台？", QuestionSelection.MULTI, listOf("Android"))
        var draft by mutableStateOf(AskUserProtocol.newDraft(listOf(question)))
        compose.setContent { RikkahubTheme { AskUserQuestionnaire(listOf(question), draft, { draft = it }) } }
        compose.onNodeWithTag("ask-select-q:s0").performClick()
        compose.onNodeWithTag("ask-text-q:c0").performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("ask-input-q:c0").assertExists().performTextInput("Windows, Linux")
        compose.runOnIdle { assertEquals(setOf("q:s0", "q:c0"), draft.questions.single().selectedIds) }
        compose.onNodeWithTag("ask-add-q").performClick()
        compose.onNodeWithTag("ask-input-q:c1").assertExists().performTextInput("Web")
        compose.runOnIdle { assertEquals(setOf("q:s0", "q:c0", "q:c1"), draft.questions.single().selectedIds) }
    }
}
