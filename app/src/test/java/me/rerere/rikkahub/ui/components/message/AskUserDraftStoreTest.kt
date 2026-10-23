package me.rerere.rikkahub.ui.components.message

import androidx.lifecycle.SavedStateHandle
import me.rerere.ai.interaction.AskUserProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class AskUserDraftStoreTest {
    private val input = """{"questions":[{"id":"q","question":"语言？","options":["Kotlin"]}]}"""

    @Test
    fun `drafts restore after view model recreation and remain isolated by conversation and call`() {
        val questions = AskUserProtocol.parse(input)
        val handle = SavedStateHandle()
        val store = AskUserDraftStore("conversation", handle)
        val draft = AskUserProtocol.newDraft(questions).update("q") { it.edit(questions.single(), "q:c0", "修改后的答案") }
        store.save("call", input, draft)

        val restored = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        assertEquals(draft, AskUserDraftStore("conversation", restored).load("call", input, questions))
        assertEquals(
            AskUserProtocol.newDraft(questions),
            AskUserDraftStore("other", restored).load("call", input, questions)
        )
        assertEquals(AskUserProtocol.newDraft(questions), store.load("other-call", input, questions))
        assertEquals(AskUserProtocol.newDraft(questions), store.load("call", "$input ", questions))
        store.remove("call")
        assertEquals(AskUserProtocol.newDraft(questions), store.load("call", input, questions))
    }
}
