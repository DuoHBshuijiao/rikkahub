package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageQueueTest {
    private fun text(value: String) = listOf(UIMessagePart.Text(value))

    @Test
    fun `immediate send keeps attachments referenced until handover and preserves remaining order`() {
        val queue = ConversationInbox()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("first"))
        val parts = text("priority") + UIMessagePart.Image("file:///priority.png")
        queue.enqueue(parts, answer = false, reply = reply)
        queue.enqueue(text("last"))
        val selected = queue.state.value.messages[1]

        assertEquals(selected, queue.reserveImmediate(selected.id))
        assertEquals(listOf("first", "priority", "last"), queue.state.value.messages.map {
            (it.parts.first() as UIMessagePart.Text).text
        })
        assertNull(queue.takeNext())
        assertNull(queue.reserveImmediate(selected.id))
        assertNull(queue.beginEdit(selected.id))
        assertNull(queue.finishEdit(selected.id, text("stale edit")))

        val dispatched = queue.takeImmediate(selected.id)!!
        assertEquals(parts, dispatched.parts)
        assertFalse(dispatched.answer)
        assertTrue(dispatched.reply === reply)
        assertFalse(reply.isCompleted)
        assertEquals(text("first"), queue.takeMessage()!!.parts)
        assertEquals(text("last"), queue.takeMessage()!!.parts)
        assertNull(queue.takeImmediate(selected.id))
    }

    @Test
    fun `cancelling immediate handover leaves the paused queue and original position intact`() {
        val queue = ConversationInbox()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        val selected = queue.state.value.messages.last()
        queue.pause()
        queue.reserveImmediate(selected.id)
        queue.releaseImmediate(selected.id)

        assertTrue(queue.state.value.paused)
        assertNull(queue.state.value.sendingMessageId)
        assertNull(queue.takeNext())
        queue.reserveImmediate(selected.id)
        assertEquals(selected, queue.takeImmediate(selected.id))
        assertFalse(queue.state.value.paused)
        assertEquals(text("first"), queue.takeMessage()!!.parts)
    }

    @Test
    fun `immediate send rejects editing and removed items`() {
        val queue = ConversationInbox()
        queue.enqueue(text("draft"))
        val id = queue.state.value.messages.single().id
        queue.beginEdit(id)
        assertNull(queue.reserveImmediate(id))
        queue.finishEdit(id)
        queue.reserveImmediate(id)
        queue.remove(id)
        assertNull(queue.takeImmediate(id))
        assertNull(queue.state.value.sendingMessageId)
        assertNull(queue.reserveImmediate(id))
    }

    @Test
    fun `editing a voice message preserves its reply observer and queue position`() {
        val queue = ConversationInbox()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("original"), reply = reply)
        queue.enqueue(text("later"))
        val id = queue.state.value.messages.first().id
        queue.beginEdit(id)
        assertNull(queue.takeNext())
        queue.finishEdit(id, text("edited"))
        val dispatched = queue.takeMessage()!!
        assertEquals(text("edited"), dispatched.parts)
        assertTrue(dispatched.reply === reply)
        assertFalse(reply.isCompleted)
        assertEquals(text("later"), queue.takeMessage()!!.parts)
    }

    @Test
    fun `pausing resolves voice observers but retains queued content for manual resume`() = runBlocking {
        val queue = ConversationInbox()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("keep me"), reply = reply)
        queue.pause()
        assertTrue(reply.isCompleted)
        assertTrue(runCatching { reply.await() }.exceptionOrNull() is IllegalStateException)
        assertEquals(text("keep me"), queue.state.value.messages.single().parts)
        queue.resume()
        assertEquals(text("keep me"), queue.takeMessage()!!.parts)
    }

    @Test
    fun `tool approval can release reply observers without removing or resuming messages`() = runBlocking {
        val queue = ConversationInbox()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("pending"), reply = reply)
        queue.failReplyWaiters("approval required")
        assertEquals("approval required", runCatching { reply.await() }.exceptionOrNull()?.message)
        assertEquals(1, queue.state.value.messages.size)
        assertFalse(queue.state.value.paused)
    }

    @Test
    fun `dispatches in submission order and preserves send without answer`() {
        val queue = ConversationInbox()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"), answer = false)

        assertEquals(text("first"), queue.takeMessage()!!.parts)
        val second = queue.takeMessage()!!
        assertEquals(text("second"), second.parts)
        assertFalse(second.answer)
        assertNull(queue.takeNext())
    }

    @Test
    fun `editing the head blocks later messages and keeps its position`() {
        val queue = ConversationInbox()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        val id = queue.state.value.messages.first().id

        queue.beginEdit(id)
        assertNull(queue.takeNext())
        queue.finishEdit(id, text("edited"))

        val first = queue.takeMessage()!!
        assertEquals(id, first.id)
        assertEquals(text("edited"), first.parts)
        assertEquals(text("second"), queue.takeMessage()!!.parts)
    }

    @Test
    fun `editing a later message does not block earlier messages`() {
        val queue = ConversationInbox()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        queue.beginEdit(queue.state.value.messages.last().id)

        assertEquals(text("first"), queue.takeMessage()!!.parts)
        assertNull(queue.takeNext())
    }

    @Test
    fun `cancelling an edit restores the original input and attachments`() {
        val queue = ConversationInbox()
        val parts = text("question") + UIMessagePart.Image("file:///test.png")
        queue.enqueue(parts)
        val id = queue.state.value.messages.single().id
        queue.beginEdit(id)
        queue.finishEdit(id)

        assertEquals(parts, queue.takeMessage()!!.parts)
    }

    @Test
    fun `removing a message skips it without changing following input`() {
        val queue = ConversationInbox()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        queue.remove(queue.state.value.messages.first().id)

        assertEquals(text("second"), queue.takeMessage()!!.parts)
        assertNull(queue.takeNext())
    }

    @Test
    fun `new input and edits cannot silently resume a paused queue`() {
        val queue = ConversationInbox()
        queue.enqueue(text("first"))
        queue.pause()
        queue.enqueue(text("second"))
        val id = queue.state.value.messages.first().id
        queue.beginEdit(id)
        queue.finishEdit(id, text("edited"))

        assertTrue(queue.state.value.paused)
        assertNull(queue.takeNext())
        queue.resume()
        assertEquals(text("edited"), queue.takeMessage()!!.parts)
        assertEquals(text("second"), queue.takeMessage()!!.parts)
    }

    @Test
    fun `late edit cannot recreate a removed or dispatched message`() {
        val queue = ConversationInbox()
        queue.enqueue(text("first"))
        val id = queue.takeMessage()!!.id

        assertNull(queue.beginEdit(id))
        queue.finishEdit(id, text("late"))
        assertTrue(queue.state.value.messages.isEmpty())
    }

    @Test
    fun `rejects empty input but accepts attachment only input`() {
        val queue = ConversationInbox()
        queue.enqueue(text("  "))
        queue.enqueue(emptyList())
        assertNull(queue.takeNext())

        val parts = listOf(UIMessagePart.Image("file:///test.png"))
        queue.enqueue(parts)
        assertEquals(parts, queue.takeMessage()!!.parts)
    }

    @Test
    fun `submission snapshots caller owned list`() {
        val queue = ConversationInbox()
        val parts = mutableListOf<UIMessagePart>(UIMessagePart.Text("original"))
        queue.enqueue(parts)
        parts.clear()

        assertEquals(text("original"), queue.takeMessage()!!.parts)
    }

    @Test
    fun `queues are isolated per conversation`() {
        val first = ConversationInbox()
        val second = ConversationInbox()
        first.enqueue(text("first"))
        second.enqueue(text("second"))
        first.pause()

        assertNull(first.takeNext())
        assertEquals(text("second"), second.takeMessage()!!.parts)
    }
}

private fun ConversationInbox.takeMessage() = (takeNext() as? InboxTurn.Message)?.message
