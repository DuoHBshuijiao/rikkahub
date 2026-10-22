package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.OpenAIReasoningMetadata
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

@Suppress("DEPRECATION")
class ConversationCleanupTest {
    private val reasoning = UIMessagePart.Reasoning(
        "private reasoning",
        metadata = OpenAIReasoningMetadata("reasoning-id", "encrypted").toMetadata(),
    )
    private val tool = UIMessagePart.Tool("call", "test", "{}", listOf(UIMessagePart.Text("result")))
    private val both = ConversationCleanupOptions(reasoning = true, tools = true)

    private fun conversation(vararg messages: UIMessage) = Conversation(
        assistantId = Uuid.random(),
        messageNodes = messages.map { it.toMessageNode() },
    )

    @Test
    fun `reasoning removal preserves body attachments checkpoint and message metadata`() {
        val sourceMessage = UIMessage.assistant("answer").copy(
            parts = listOf(reasoning, UIMessagePart.Text("answer"), UIMessagePart.Image("file://image"), tool),
            isContextCheckpoint = true,
            modelId = Uuid.random(),
            generationTimeMs = 123,
        )
        val source = conversation(sourceMessage)
        val cleaned = source.cleanupContent(ConversationCleanupOptions(reasoning = true))
        assertEquals(sourceMessage.copy(parts = sourceMessage.parts.drop(1)), cleaned.currentMessages.single())
        assertEquals(sourceMessage, source.currentMessages.single())
    }

    @Test
    fun `tool removal covers local server and legacy call and result without removing reasoning`() {
        val source = conversation(UIMessage.assistant("answer").copy(parts = listOf(
            reasoning,
            tool,
            UIMessagePart.ServerTool("server", "search", status = ServerToolStatus.COMPLETED),
            UIMessagePart.ToolCall("legacy", "test", "{}"),
            UIMessagePart.ToolResult("legacy", "test", JsonPrimitive("result"), JsonPrimitive("args")),
            UIMessagePart.Text("answer"),
        )))
        val cleaned = source.cleanupContent(ConversationCleanupOptions(tools = true))
        assertEquals(listOf(reasoning, UIMessagePart.Text("answer")), cleaned.currentMessages.single().parts)
        assertTrue(cleaned.currentMessages.single().getTools().isEmpty())
        assertEquals(listOf(UIMessagePart.Text("answer")), source.cleanupContent(both).currentMessages.single().parts)
    }

    @Test
    fun `all alternatives are cleaned and selected surviving version retains its identity`() {
        val versions =
            List(
                3
            ) {
                UIMessage.assistant(
                    "answer $it"
                ).copy(parts = listOf(reasoning, tool, UIMessagePart.Text("answer $it")))
            }
        val source = conversation().copy(messageNodes = listOf(MessageNode(messages = versions, selectIndex = 2)))
        val node = source.cleanupContent(both).messageNodes.single()
        assertEquals(versions.map { it.id }, node.messages.map { it.id })
        assertEquals(2, node.selectIndex)
        assertTrue(node.messages.all { it.parts.size == 1 })
    }

    @Test
    fun `empty alternatives are removed and selection falls back to nearest previous survivor`() {
        val emptyAfter = UIMessage.assistant("").copy(parts = listOf(reasoning, tool))
        val versions = listOf(
            emptyAfter,
            UIMessage.assistant("first"),
            emptyAfter.copy(id = Uuid.random()),
            UIMessage.assistant("previous"),
            emptyAfter.copy(id = Uuid.random()),
            UIMessage.assistant("next")
        )
        for ((selected, expectedText) in listOf(0 to "first", 2 to "first", 4 to "previous", 5 to "next")) {
            val source = conversation().copy(
                messageNodes = listOf(MessageNode(messages = versions, selectIndex = selected))
            )
            val node = source.cleanupContent(both).messageNodes.single()
            assertEquals(listOf("first", "previous", "next"), node.messages.map { it.toText() })
            assertEquals(expectedText, node.currentMessage.toText())
        }
        assertTrue(conversation(emptyAfter).cleanupContent(both).messageNodes.isEmpty())
    }

    @Test
    fun `no match disabled options and repeated cleanup are no ops`() {
        val source = conversation(UIMessage.assistant("answer"))
        assertSame(source, source.cleanupContent(both))
        val withReasoning = conversation(UIMessage.assistant("").copy(parts = listOf(reasoning)))
        assertSame(withReasoning, withReasoning.cleanupContent(ConversationCleanupOptions()))
        val cleaned = withReasoning.cleanupContent(both)
        assertSame(cleaned, cleaned.cleanupContent(both))
        assertFalse(withReasoning.messageNodes.isEmpty())
    }

    @Test
    fun `reasoning nested inside tool output is removed without making tool executable again`() {
        val source = conversation(UIMessage.assistant("").copy(parts = listOf(tool.copy(output = listOf(reasoning)))))
        val result = source.cleanupContent(
            ConversationCleanupOptions(reasoning = true)
        ).currentMessages.single().getTools().single()
        assertTrue(result.isExecuted)
        assertFalse(result.output.any { it is UIMessagePart.Reasoning })
    }
}
