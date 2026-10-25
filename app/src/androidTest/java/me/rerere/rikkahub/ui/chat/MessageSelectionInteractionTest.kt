package me.rerere.rikkahub.ui.chat

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.chrisbanes.haze.rememberHazeState
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.pages.chat.ChatList
import me.rerere.rikkahub.ui.pages.chat.MessageSelection
import me.rerere.rikkahub.ui.pages.chat.MessageSelectionList
import me.rerere.rikkahub.ui.pages.chat.MessageSelectionToolbar
import me.rerere.rikkahub.ui.pages.chat.MessageSelectionTopBar
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.junit.Rule
import org.junit.Test
import kotlin.uuid.Uuid

class MessageSelectionInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val nodes = listOf(
        MessageNode(messages = listOf(UIMessage.user("第一条"))),
        MessageNode(messages = listOf(UIMessage.assistant("第二条长回复".repeat(2000)))),
        MessageNode(messages = listOf(UIMessage.user("第三条"))),
    )
    private val conversation = Conversation(assistantId = Uuid.random(), messageNodes = nodes)

    private fun show(): StateRestorationTester {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            var selection by rememberSaveable(
                stateSaver = MessageSelection.StateSaver
            ) { mutableStateOf(MessageSelection.begin(nodes[0].id)) }
            RikkahubTheme {
                Scaffold(
                    topBar = { MessageSelectionTopBar(selection, nodes.size, { selection = it }, {}) },
                    bottomBar = { MessageSelectionToolbar(selection, conversation, { selection = it }, {}) },
                ) { padding -> MessageSelectionList(conversation, selection, { selection = it }, padding) }
            }
        }
        return restoration
    }

    @Test
    fun longRepliesRemainCompactAndOnlySelectedSupportsUndo() {
        show()
        compose.onNodeWithTag("message-selection-${nodes[2].id}").assertIsDisplayed().performClick()
        compose.onNodeWithText("已选 2 / 共 3 条").assertIsDisplayed()
        compose.onNodeWithText("仅看已选").performClick()
        compose.onNodeWithTag("message-selection-${nodes[1].id}").assertDoesNotExist()
        compose.onNodeWithTag("message-selection-${nodes[2].id}").performClick()
        compose.onNodeWithTag("message-selection-${nodes[2].id}").assertDoesNotExist()
        compose.onNodeWithText("撤销").performClick()
        compose.onNodeWithTag("message-selection-${nodes[2].id}").assertIsDisplayed()
    }

    @Test
    fun rangeSelectionAndFilterSurviveStateRestoration() {
        val restoration = show()
        compose.onNodeWithText("范围选择").performClick()
        compose.onNodeWithTag("message-selection-${nodes[2].id}").performClick()
        compose.onNodeWithTag("message-selection-${nodes[0].id}").performClick()
        compose.onNodeWithText("已选 3 / 共 3 条").assertIsDisplayed()
        compose.onNodeWithText("仅看已选").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("已选 3 / 共 3 条").assertIsDisplayed()
        compose.onNodeWithTag("message-selection-${nodes[2].id}").assertIsDisplayed()
    }

    @Test
    fun readingFullTextDoesNotToggleSelection() {
        show()
        compose.onAllNodesWithContentDescription("查看全文")[1].performClick()
        compose.onNodeWithText("消息全文").assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("已选 1 / 共 3 条").assertIsDisplayed()
    }

    @Test
    fun longPressInPreviewStartsSelectionWithOnlyThatMessage() {
        compose.setContent {
            var selection by rememberSaveable(
                stateSaver = MessageSelection.StateSaver
            ) { mutableStateOf(MessageSelection()) }
            RikkahubTheme {
                Scaffold(topBar = {
                    if (selection.active) MessageSelectionTopBar(selection, nodes.size, { selection = it }, {})
                }) { padding ->
                    ChatList(
                        innerPadding = padding,
                        conversation = conversation,
                        state = rememberLazyListState(),
                        loading = false,
                        previewMode = true,
                        selection = selection,
                        onSelectionChange = { selection = it },
                        onSelectMessage = { selection = MessageSelection.begin(it) },
                        settings = Settings(),
                        hazeState = rememberHazeState(),
                    )
                }
            }
        }
        compose.onNodeWithText("第一条").performTouchInput { longClick() }
        compose.onNodeWithText("已选 1 / 共 3 条").assertIsDisplayed()
        compose.onNodeWithTag("message-selection-${nodes[0].id}").assertIsDisplayed()
    }
}
