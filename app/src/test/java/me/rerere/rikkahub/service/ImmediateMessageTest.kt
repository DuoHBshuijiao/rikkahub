package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ImmediateMessageTest {
    @Test
    fun `immediate message waits for all final saves and replaced jobs cannot advance queue`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val completions = mutableListOf<Throwable?>()
        var persisted = Conversation.ofId(id)
        val store = object : ConversationStore {
            override suspend fun prepareWrite(): (Conversation) -> Conversation = { it }
            override suspend fun write(previous: Conversation?, current: Conversation) {
                persisted = current
            }
        }
        val session = ConversationSession(id, persisted, scope, store, {}, { _, cause -> completions.add(cause) })
        try {
            session.initialize(load = { persisted })
            val finalSave = CompletableDeferred<Unit>()
            val old = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        finalSave.await()
                        session.update {
                            it.copy(
                                messageNodes = listOf(UIMessage.assistant("partial").toMessageNode())
                            )
                        }
                        session.flush()
                    }
                }
            }
            session.setJob(old)
            val approval = scope.launch(start = CoroutineStart.LAZY) {
                afterPreviousGeneration(old) { error("An interrupted approval must not execute") }
            }
            session.setJob(approval, cancelPrevious = false)
            session.messageQueue.enqueue(listOf(UIMessagePart.Text("earlier")))
            session.messageQueue.enqueue(
                listOf(UIMessagePart.Text("new prompt"), UIMessagePart.Image("file:///test.png"))
            )
            val selected = session.messageQueue.state.value.messages.last()
            val sent = mutableListOf<QueuedMessage>()

            val immediate = session.startImmediateMessage(
                selected.id,
                createJob = { block -> scope.launch(start = CoroutineStart.LAZY) { block() } },
                send = { message ->
                    assertTrue(old.isCompleted)
                    assertTrue(approval.isCompleted)
                    assertEquals(
                        "partial",
                        (persisted.currentMessages.single().parts.single() as UIMessagePart.Text).text
                    )
                    assertSame(message, session.submittingMessage)
                    sent.add(message)
                },
            )!!

            assertTrue(old.isCancelled)
            assertTrue(sent.isEmpty())
            assertTrue(completions.isEmpty())
            assertSame(immediate, session.getJob())
            assertEquals(2, session.messageQueue.state.value.messages.size)
            assertNull(session.messageQueue.takeNext())
            assertNull(session.startImmediateMessage(selected.id, { error("Duplicate job") }, {}))

            finalSave.complete(Unit)
            immediate.join()

            assertEquals(listOf(selected), sent)
            assertEquals(listOf<Throwable?>(null), completions)
            assertEquals(listOf(UIMessagePart.Text("earlier")), session.messageQueue.takeNext()!!.parts)
            assertNull(session.messageQueue.state.value.sendingMessageId)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `stopping handover waits for predecessor and preserves the queued input`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial = Conversation.ofId(Uuid.random())
        val session = ConversationSession(initial.id, initial, scope, noOpStore(), {})
        try {
            val finalSave = CompletableDeferred<Unit>()
            val old = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { finalSave.await() }
                }
            }
            session.setJob(old)
            session.messageQueue.enqueue(listOf(UIMessagePart.Text("keep me")))
            val selected = session.messageQueue.state.value.messages.single()
            var sent = false
            val immediate = session.startImmediateMessage(
                selected.id,
                createJob = { block -> scope.launch(start = CoroutineStart.LAZY) { block() } },
                send = { sent = true },
            )!!

            session.messageQueue.pause()
            val stopped = session.cancelJobs()
            assertFalse(immediate.isCompleted)
            finalSave.complete(Unit)
            stopped.forEach { it.join() }

            assertFalse(sent)
            assertTrue(old.isCompleted)
            assertTrue(immediate.isCancelled)
            assertEquals(listOf(selected), session.messageQueue.state.value.messages)
            assertTrue(session.messageQueue.state.value.paused)
            assertNull(session.messageQueue.state.value.sendingMessageId)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `a discarded item cannot be recreated by a late handover`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial = Conversation.ofId(Uuid.random())
        val session = ConversationSession(initial.id, initial, scope, noOpStore(), {})
        try {
            val finalSave = CompletableDeferred<Unit>()
            session.setJob(scope.launch(start = CoroutineStart.LAZY) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { finalSave.await() }
                }
            })
            session.messageQueue.enqueue(listOf(UIMessagePart.Text("discard me")))
            val selected = session.messageQueue.state.value.messages.single()
            var sent = false
            val immediate = session.startImmediateMessage(
                selected.id,
                createJob = { block -> scope.launch(start = CoroutineStart.LAZY) { block() } },
                send = { sent = true },
            )!!
            session.messageQueue.remove(selected.id)
            finalSave.complete(Unit)
            immediate.join()

            assertFalse(sent)
            assertTrue(session.messageQueue.state.value.messages.isEmpty())
            assertNull(session.submittingMessage)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    private fun noOpStore() = object : ConversationStore {
        override suspend fun prepareWrite(): (Conversation) -> Conversation = { it }
        override suspend fun write(previous: Conversation?, current: Conversation) = Unit
    }
}
