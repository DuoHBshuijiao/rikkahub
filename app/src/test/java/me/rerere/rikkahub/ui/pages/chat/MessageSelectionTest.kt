package me.rerere.rikkahub.ui.pages.chat

import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MessageSelectionTest {
    private val ids = List(6) { Uuid.random() }

    @Test
    fun `entering selection chooses only the initiating message`() {
        val selection = MessageSelection.begin(ids[3])
        assertTrue(selection.active)
        assertEquals(setOf(ids[3]), selection.selectedIds)
        assertEquals(ids[3], selection.initialNodeId)
        assertTrue(MessageSelection().selectedIds.isEmpty())
    }

    @Test
    fun `range includes endpoints in either direction and retains unrelated selections`() {
        for ((start, end) in listOf(1 to 4, 4 to 1, 2 to 2)) {
            val selection = MessageSelection.begin(ids[5]).copy(choosingRange = true)
                .choose(ids[start], ids).choose(ids[end], ids)
            assertEquals(
                (ids.subList(minOf(start, end), maxOf(start, end) + 1) + ids[5]).toSet(),
                selection.selectedIds
            )
            assertFalse(selection.choosingRange)
            assertNull(selection.rangeStart)
        }
    }

    @Test
    fun `deletion removes selection and range anchor without selecting newly added nodes`() {
        val selection = MessageSelection.begin(
            ids[0]
        ).copy(selectedIds = setOf(ids[0], ids[2]), choosingRange = true, rangeStart = ids[0])
        val remaining = listOf(ids[1], ids[2], ids[3], Uuid.random())
        val updated = selection.reconcile(remaining.toSet())
        assertEquals(setOf(ids[2]), updated.selectedIds)
        assertNull(updated.rangeStart)
        assertNull(updated.initialNodeId)
        assertEquals(ids[3], updated.choose(ids[3], remaining).rangeStart)
    }

    @Test
    fun `export follows conversation order and selected answer versions rather than click order`() {
        val oldAnswer = UIMessage.assistant("old answer")
        val currentAnswer = UIMessage.assistant("current answer")
        val nodes = listOf(
            MessageNode(id = ids[0], messages = listOf(UIMessage.user("question"))),
            MessageNode(id = ids[1], messages = listOf(oldAnswer, currentAnswer), selectIndex = 1),
            MessageNode(id = ids[2], messages = listOf(UIMessage.user("unselected"))),
        )
        val selection = MessageSelection.begin(ids[1]).toggle(ids[0])
        assertEquals(listOf(nodes[0].currentMessage, currentAnswer), selection.selectedMessages(nodes))
        assertEquals(listOf(nodes[0].currentMessage, oldAnswer), selection.selectedMessages(nodes.map {
            if (it.id == ids[1]) it.copy(selectIndex = 0) else it
        }))
    }

    @Test
    fun `save and restore retains filter range and selected IDs`() {
        val selection = MessageSelection.begin(
            ids[3]
        ).copy(onlySelected = true, choosingRange = true, rangeStart = ids[1])
        assertEquals(
            selection,
            Json.decodeFromString(
                MessageSelection.serializer(),
                Json.encodeToString(MessageSelection.serializer(), selection)
            )
        )
    }

    @Test
    fun `summaries bound long messages and identify attachments and tool-only replies`() {
        val longText = UIMessage.assistant("正文".repeat(10000))
        assertEquals(300, longText.selectionSummary().length)
        val attachment = UIMessage.user(
            ""
        ).copy(parts = listOf(UIMessagePart.Document("file:///a.pdf", "报告.pdf"), UIMessagePart.Image("file:///a.png")))
        assertTrue(attachment.selectionSummary().contains("[文件] 报告.pdf"))
        assertTrue(attachment.selectionSummary().contains("[图片]"))
        val tool = UIMessage.assistant(
            ""
        ).copy(parts = listOf(UIMessagePart.Tool("call", "search", "query", listOf(UIMessagePart.Text("result")))))
        assertEquals("[工具调用] search", tool.selectionSummary())
        assertTrue(tool.selectionSummary(Int.MAX_VALUE).contains("result"))
        val answerAfterTools = tool.copy(parts = List(20) { tool.parts.single() } + UIMessagePart.Text("正文应优先显示"))
        assertTrue(answerAfterTools.selectionSummary().startsWith("正文应优先显示"))
    }
}
