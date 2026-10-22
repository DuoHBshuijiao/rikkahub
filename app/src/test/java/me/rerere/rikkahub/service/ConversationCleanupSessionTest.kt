package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationCleanupOptions
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationCleanupSessionTest {
    private val options = ConversationCleanupOptions(reasoning = true, tools = true)
    private fun source() = Conversation(
        assistantId = Uuid.random(),
        messageNodes = listOf(UIMessage.assistant("answer").copy(parts = listOf(
            UIMessagePart.Reasoning("private"),
            UIMessagePart.Text("answer"),
            UIMessagePart.Tool("call", "test", "{}", listOf(UIMessagePart.Text("result"))),
        )).toMessageNode()),
    )

    @Test
    fun `cleanup persists across reopening and leaves another conversation unchanged`() = runTest {
        val source = source()
        val store = FakeConversationStore(source)
        val session = ConversationSession(source.id, source, backgroundScope, store, {})
        session.initialize(load = { store.persisted })
        val otherSource = source()
        val other =
            ConversationSession(otherSource.id, otherSource, backgroundScope, FakeConversationStore(otherSource), {})
        other.initialize(load = { otherSource })
        val untouched = other.state.value
        assertTrue(session.cleanupContent(options))
        session.flush()
        val reopened = ConversationSession(source.id, source, backgroundScope, store, {})
        reopened.initialize(load = { store.persisted })
        assertEquals(listOf(UIMessagePart.Text("answer")), reopened.state.value.currentMessages.single().parts)
        assertTrue(reopened.state.value.currentMessages.single().getTools().isEmpty())
        assertFalse(reopened.cleanupContent(options))
        assertSame(untouched, other.state.value)
    }

    @Test
    fun `generation and approval prevent cleanup without changing state`() = runTest {
        val source = source()
        val session = ConversationSession(source.id, source, backgroundScope, FakeConversationStore(source), {})
        session.initialize(load = { source })
        val job = backgroundScope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
        session.setJob(job)
        assertThrows(IllegalStateException::class.java) { session.cleanupContent(options) }
        assertSame(source, session.state.value)
        job.cancel()
        job.join()
        session.update { latest ->
            latest.copy(messageNodes = listOf(
                UIMessage.assistant("").copy(parts = listOf(
                    UIMessagePart.Tool("pending", "test", "{}", approvalState = ToolApprovalState.Pending),
                )).toMessageNode(),
            ))
        }
        val pending = session.state.value
        assertThrows(IllegalStateException::class.java) { session.cleanupContent(options) }
        assertSame(pending, session.state.value)
    }

    @Test
    fun `failed persistence can be retried without bringing removed parts back`() = runTest {
        val source = source()
        val store = FakeConversationStore(source)
        val session = ConversationSession(source.id, source, backgroundScope, store, {})
        session.initialize(load = { source })
        session.cleanupContent(options)
        store.beforeWrite = { error("disk unavailable") }
        try {
            session.flush()
            error("Expected failure")
        } catch (e: IllegalStateException) {
            assertEquals("disk unavailable", e.message)
        }
        store.beforeWrite = {}
        assertFalse(session.cleanupContent(options))
        session.flush()
        assertEquals(listOf(UIMessagePart.Text("answer")), store.persisted!!.currentMessages.single().parts)
    }
}
