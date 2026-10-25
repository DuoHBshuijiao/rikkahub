package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.runtime.saveable.Saver
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.model.MessageNode
import kotlin.uuid.Uuid

/** UI-only selection. Node IDs remain stable as answers stream or change versions. */
@Serializable
data class MessageSelection(
    val active: Boolean = false,
    val selectedIds: Set<Uuid> = emptySet(),
    val onlySelected: Boolean = false,
    val choosingRange: Boolean = false,
    val rangeStart: Uuid? = null,
    val initialNodeId: Uuid? = null,
) {
    fun toggle(id: Uuid) = copy(
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id,
    )

    fun choose(id: Uuid, orderedIds: List<Uuid>): MessageSelection {
        if (id !in orderedIds) return this
        if (!choosingRange) return toggle(id)
        val start = orderedIds.indexOf(rangeStart)
        if (start < 0) return copy(rangeStart = id)
        val end = orderedIds.indexOf(id)
        return copy(
            selectedIds = selectedIds + orderedIds.subList(minOf(start, end), maxOf(start, end) + 1),
            choosingRange = false,
            rangeStart = null,
        )
    }

    fun reconcile(ids: Set<Uuid>) = copy(
        selectedIds = selectedIds.intersect(ids),
        rangeStart = rangeStart?.takeIf { it in ids },
        initialNodeId = initialNodeId?.takeIf { it in ids },
    )

    fun selectedMessages(nodes: List<MessageNode>) = nodes.filter { it.id in selectedIds }.map { it.currentMessage }

    companion object {
        fun begin(id: Uuid) = MessageSelection(active = true, selectedIds = setOf(id), initialNodeId = id)

        val StateSaver = Saver<MessageSelection, String>(
            save = { Json.encodeToString(serializer(), it) },
            restore = { Json.decodeFromString(serializer(), it) },
        )
    }
}
