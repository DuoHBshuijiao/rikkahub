package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.limitContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationCheckpointTest {
    @Test
    fun `compression replaces the history prefix and preserves recent nodes`() {
        val nodes = List(4) { UIMessage.user("message $it").toMessageNode() }
        val source = Conversation(assistantId = Uuid.random(), messageNodes = nodes)

        val result = source.replaceCompressedHistory(expectedNodes = nodes.take(2), summary = "summary")!!

        assertEquals(3, result.messageNodes.size)
        assertEquals(nodes.drop(2), result.messageNodes.drop(1))
        val checkpoint = result.messageNodes.first().currentMessage
        assertEquals(MessageRole.USER, checkpoint.role)
        assertTrue(checkpoint.isContextCheckpoint)
        assertEquals("summary", checkpoint.toText())
        assertEquals(result.currentMessages, result.currentMessages.limitContext(0))
        assertEquals(nodes, source.messageNodes)
    }

    @Test
    fun `compression of all messages removes alternate versions and clears suggestions`() {
        val node = MessageNode(messages = listOf(UIMessage.user("first"), UIMessage.user("alternate")), selectIndex = 1)
        val source = Conversation(
            assistantId = Uuid.random(),
            title = "conversation",
            messageNodes = listOf(node, UIMessage.assistant("answer").toMessageNode()),
            chatSuggestions = listOf("stale suggestion"),
            customSystemPrompt = "system",
        )

        val result = source.replaceCompressedHistory(source.messageNodes, "summary")!!

        assertEquals(listOf("summary"), result.currentMessages.map { it.toText() })
        assertEquals(1, result.messageNodes.single().messages.size)
        assertTrue(result.chatSuggestions.isEmpty())
        assertEquals(source.id, result.id)
        assertEquals(source.title, result.title)
        assertEquals(source.customSystemPrompt, result.customSystemPrompt)
    }

    @Test
    fun `next compression removes legacy history and all previous checkpoints`() {
        val source = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(
                UIMessage.user("old history"),
                UIMessage.user("first summary").copy(isContextCheckpoint = true),
                UIMessage.assistant("more history"),
                UIMessage.user("second summary").copy(isContextCheckpoint = true),
                UIMessage.user("new message"),
            ).map { it.toMessageNode() },
        )

        val result = source.replaceCompressedHistory(source.messageNodes, "third summary")!!
        val continued = result.copy(messageNodes = result.messageNodes + UIMessage.assistant("reply").toMessageNode())
        val again = continued.replaceCompressedHistory(continued.messageNodes, "fourth summary")!!

        assertEquals(listOf("third summary"), result.currentMessages.map { it.toText() })
        assertEquals(listOf("fourth summary"), again.currentMessages.map { it.toText() })
        assertEquals(1, again.currentMessages.count { it.isContextCheckpoint })
    }

    @Test
    fun `compression preserves updated recent nodes appended messages and latest metadata`() {
        val nodes = List(3) { UIMessage.user("message $it").toMessageNode() }
        val source = Conversation(assistantId = Uuid.random(), messageNodes = nodes)
        val updatedRecent = nodes.last().copy(messages = listOf(UIMessage.user("edited recent")))
        val appended = UIMessage.assistant("appended").toMessageNode()
        val latest = source.copy(title = "new title", messageNodes = nodes.take(2) + updatedRecent + appended)

        val result = latest.replaceCompressedHistory(nodes.take(2), "summary")!!

        assertEquals(listOf(updatedRecent, appended), result.messageNodes.drop(1))
        assertSame(updatedRecent, result.messageNodes[1])
        assertEquals("new title", result.title)
    }

    @Test
    fun `compression rejects missing edited reordered or reselected history`() {
        val first = UIMessage.user("question").toMessageNode()
        val second = MessageNode(messages = listOf(UIMessage.assistant("first"), UIMessage.assistant("second")))
        val source = Conversation(assistantId = Uuid.random(), messageNodes = listOf(first, second))
        val expected = source.messageNodes

        assertNull(source.replaceCompressedHistory(emptyList(), "summary"))
        assertNull(source.copy(messageNodes = listOf(second)).replaceCompressedHistory(expected, "summary"))
        assertNull(source.copy(messageNodes = listOf(second, first)).replaceCompressedHistory(expected, "summary"))
        val edited = second.copy(messages = listOf(UIMessage.assistant("edited")))
        assertNull(source.copy(messageNodes = listOf(first, edited)).replaceCompressedHistory(expected, "summary"))
        val reselected = second.copy(selectIndex = 1)
        assertNull(source.copy(messageNodes = listOf(first, reselected)).replaceCompressedHistory(expected, "summary"))
        val compressed = source.replaceCompressedHistory(expected, "summary")!!
        assertNull(compressed.replaceCompressedHistory(expected, "duplicate summary"))
    }

    @Test
    fun `compressed fork keeps source history and only copies retained attachments`() {
        val source = Conversation(
            assistantId = Uuid.random(),
            title = "Chat",
            messageNodes = listOf("old", "recent").map { name ->
                UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image("file:///$name.png")))
                    .toMessageNode()
            },
            config = ConversationConfig(chatModelId = Uuid.random()),
            workspaceCwd = "/workspace",
            folderId = Uuid.random(),
        )
        val copied = mutableListOf<String>()
        val compressed = source.replaceCompressedHistory(source.messageNodes.take(1), "summary")!!

        val fork = compressed.fork(compressed.currentMessages.last().id, setOf("Chat(1)")) { url ->
            copied.add(url)
            "$url.copy"
        }!!

        assertEquals(listOf("file:///recent.png"), copied)
        assertEquals(2, source.messageNodes.size)
        assertFalse(source.currentMessages.any { it.isContextCheckpoint })
        assertEquals("file:///recent.png", (source.currentMessages.last().parts.single() as UIMessagePart.Image).url)
        assertEquals("file:///recent.png.copy", (fork.currentMessages.last().parts.single() as UIMessagePart.Image).url)
        assertEquals("summary", fork.currentMessages.first().toText())
        assertTrue(fork.currentMessages.first().isContextCheckpoint)
        assertEquals("Chat(2)", fork.title)
        assertEquals(source.config, fork.config)
        assertEquals(source.workspaceCwd, fork.workspaceCwd)
        assertEquals(source.folderId, fork.folderId)
    }
}
