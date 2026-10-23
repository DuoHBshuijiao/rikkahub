package me.rerere.rikkahub.ui.components.message

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.SavedStateHandle
import kotlinx.serialization.Serializable
import me.rerere.ai.interaction.AskUserDraft
import me.rerere.ai.interaction.AskUserProtocol
import me.rerere.ai.interaction.UserQuestion

val LocalAskUserDraftStore = staticCompositionLocalOf<AskUserDraftStore?> { null }

/** Client-only drafts stay out of the conversation and are isolated by conversation and tool call. */
class AskUserDraftStore(private val conversationId: String, private val state: SavedStateHandle) {
    fun load(toolCallId: String, input: String, questions: List<UserQuestion>): AskUserDraft {
        val saved = state.get<String>(key(toolCallId))?.let {
            runCatching { AskUserProtocol.json.decodeFromString<SavedDraft>(it) }.getOrNull()
        }
        return saved?.takeIf {
            it.input == input && it.draft.questions.map { draft -> draft.questionId } == questions.map { q -> q.id }
        }?.draft ?: AskUserProtocol.newDraft(questions)
    }

    fun save(toolCallId: String, input: String, draft: AskUserDraft) {
        state[key(toolCallId)] = AskUserProtocol.json.encodeToString(SavedDraft(input, draft))
    }

    fun remove(toolCallId: String) {
        state.remove<String>(key(toolCallId))
    }

    private fun key(toolCallId: String) = "ask_user:$conversationId:$toolCallId"

    @Serializable
    private data class SavedDraft(val input: String, val draft: AskUserDraft)
}
