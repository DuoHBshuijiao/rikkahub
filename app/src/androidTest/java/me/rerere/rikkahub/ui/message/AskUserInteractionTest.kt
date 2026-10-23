package me.rerere.rikkahub.ui.message

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import me.rerere.ai.interaction.AskUserProtocol
import me.rerere.ai.interaction.QuestionSelection
import me.rerere.ai.interaction.UserQuestion
import me.rerere.rikkahub.ui.components.message.AskUserQuestionnaire
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AskUserInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun textRequiresDoubleTapWhileTheSelectionControlNeverStartsEditing() {
        val question = UserQuestion("q", "语言？", QuestionSelection.SINGLE, listOf("Kotlin", "Java"))
        var draft by mutableStateOf(AskUserProtocol.newDraft(listOf(question)))
        compose.setContent { RikkahubTheme { AskUserQuestionnaire(listOf(question), draft, { draft = it }) } }

        compose.onNodeWithTag("ask-text-q:s0").performClick()
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
        compose.onNodeWithTag("ask-input-q:c0").assertExists().performTextInput("Windows, Linux")
        compose.runOnIdle { assertEquals(setOf("q:s0", "q:c0"), draft.questions.single().selectedIds) }
        compose.onNodeWithTag("ask-add-q").performClick()
        compose.onNodeWithTag("ask-input-q:c1").assertExists().performTextInput("Web")
        compose.runOnIdle { assertEquals(setOf("q:s0", "q:c0", "q:c1"), draft.questions.single().selectedIds) }
    }
}
