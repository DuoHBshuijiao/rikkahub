package me.rerere.rikkahub.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.InboxState
import me.rerere.rikkahub.service.QueuedMessage
import me.rerere.rikkahub.ui.components.ai.MessageQueuePanel
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.uuid.Uuid

class MessageQueueInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun directSendRemainsAvailableWithNarrowWidthAndLargeText() {
        val message = QueuedMessage(parts = listOf(UIMessagePart.Text("改用这个新提示继续")))
        var sent: Uuid? = null
        compose.setContent {
            RikkahubTheme(colorMode = ColorMode.DARK) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    Box(Modifier.width(240.dp)) {
                        MessageQueuePanel(
                            state = InboxState(items = listOf(message)),
                            onRemove = {},
                            onBeginEdit = { null },
                            onFinishEdit = { _, _ -> },
                            onResume = {},
                            onSendNow = { sent = it },
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("1. 改用这个新提示继续").assertExists()
        compose.onNodeWithTag("chat_queue_send_now_${message.id}").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(message.id, sent) }
    }

    @Test
    fun editingAndHandoverPreventDuplicateSend() {
        val editing = QueuedMessage(parts = listOf(UIMessagePart.Text("editing")), isEditing = true)
        val sending = QueuedMessage(parts = listOf(UIMessagePart.Text("sending")))
        compose.setContent {
            RikkahubTheme {
                MessageQueuePanel(
                    state = InboxState(items = listOf(editing, sending), sendingMessageId = sending.id),
                    onRemove = {},
                    onBeginEdit = { null },
                    onFinishEdit = { _, _ -> },
                    onResume = {},
                    onSendNow = {},
                )
            }
        }
        compose.onNodeWithTag("chat_queue_send_now_${editing.id}").assertIsNotEnabled()
        compose.onNodeWithTag("chat_queue_send_now_${sending.id}").assertIsNotEnabled()
        compose.onNodeWithTag("chat_queue_edit_${sending.id}").assertIsNotEnabled()
    }
}
