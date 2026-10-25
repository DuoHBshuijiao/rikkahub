package me.rerere.rikkahub.data.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime
import kotlin.uuid.Uuid

class ChatExportTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val tool = UIMessagePart.Tool("call-1", "lookup", "{\"q\":\"test\"}", listOf(
        UIMessagePart.Text("tool result"), UIMessagePart.Reasoning("nested thought"),
    ))
    private val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
        UIMessagePart.Reasoning("private thought"), tool,
        UIMessagePart.ServerTool("server-1", "web_search", status = ServerToolStatus.COMPLETED),
        UIMessagePart.Text("answer"),
    ))
    private val messages = listOf(UIMessage.user("question"), assistant)

    @Test
    fun `all filter combinations retain the right content without changing original messages`() {
        for (tools in listOf(false, true)) for (reasoning in listOf(false, true)) for (assistantOnly in listOf(false, true)) {
            val filtered = filterExportMessages(messages, ExportOptions(includeReasoning = reasoning, includeToolCalls = tools, assistantOnly = assistantOnly))
            assertEquals(if (assistantOnly) 1 else 2, filtered.size)
            val parts = filtered.last().parts
            assertEquals(reasoning, parts.any { it is UIMessagePart.Reasoning })
            assertEquals(tools, parts.any { it is UIMessagePart.Tool })
            assertEquals(tools, parts.any { it is UIMessagePart.ServerTool })
            if (tools) assertEquals(reasoning, parts.filterIsInstance<UIMessagePart.Tool>().single().output.any { it is UIMessagePart.Reasoning })
        }
        assertEquals(4, assistant.parts.size)
        assertEquals(2, tool.output.size)
    }

    @Test
    fun `selected versions are exported without context checkpoint clipping`() {
        val older = UIMessage.user("older history")
        val selected = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("selected answer")), isContextCheckpoint = true)
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
            MessageNode(messages = listOf(older)),
            MessageNode(messages = listOf(assistant, selected), selectIndex = 1),
        ))
        val markdown = exportChatMarkdown("title", conversation.currentMessages, ExportOptions())
        assertTrue(markdown.contains("older history"))
        assertTrue(markdown.contains("selected answer"))
        assertFalse(markdown.contains("private thought"))
    }

    @Test
    fun `reasoning-only messages disappear when excluded`() {
        val reasoning = assistant.copy(parts = listOf(UIMessagePart.Reasoning("thought")))
        assertTrue(filterExportMessages(listOf(reasoning), ExportOptions(includeReasoning = false)).isEmpty())
        assertTrue(filterExportMessages(listOf(UIMessage.user("question")), ExportOptions(assistantOnly = true)).isEmpty())
    }

    @Test
    fun `markdown keeps malformed tool input nested fences and all attachment types`() {
        val message = assistant.copy(parts = listOf(
            tool.copy(input = "not json\n```", output = listOf(UIMessagePart.Text("```text\nresult\n```"))),
            UIMessagePart.Image("https://example.com/image.png"),
            UIMessagePart.Document("file:///report.pdf", "报告.pdf", "application/pdf"),
            UIMessagePart.Video("file:///video.mp4"), UIMessagePart.Audio("file:///audio.wav"),
        ))
        val markdown = exportChatMarkdown("title", listOf(message), ExportOptions())
        assertTrue(markdown.contains("````text\nnot json\n```\n````"))
        assertTrue(markdown.contains("````text\n```text\nresult\n```\n````"))
        assertTrue(markdown.contains("报告.pdf"))
        assertTrue(markdown.contains("video.mp4"))
        assertTrue(markdown.contains("audio.wav"))
        assertTrue(markdown.contains("https://example.com/image.png"))
    }

    @Test
    fun `file names have safe titles and matching extensions`() {
        val time = LocalDateTime.of(2026, 10, 25, 12, 30, 0)
        assertEquals("a_b_c-2026-10-25_12-30-00.json", chatExportFileName("a/b\\c", ChatExportFormat.JSON, time))
        assertEquals("chat-export-2026-10-25_12-30-00.md", chatExportFileName(" . ", ChatExportFormat.MARKDOWN, time))
    }

    private fun body(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `missing nested attachments fail clearly instead of being silently dropped`() {
        val file = temporaryFolder.newFile("image with spaces.png")
        val nested = listOf(assistant.copy(parts = listOf(tool.copy(output = listOf(UIMessagePart.Image(file.toURI().toString()))))))
        validateExportAttachments(nested)
        assertTrue(file.delete())
        val failure = runCatching { validateExportAttachments(nested) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("本地附件"))
    }

    @Test
    fun `long transcripts retain their first and last messages`() {
        val transcript = (0..1999).map { UIMessage.user("message-$it") }
        val markdown = exportChatMarkdown("Long chat", transcript, ExportOptions())
        assertTrue(markdown.contains("message-0\n"))
        assertTrue(markdown.contains("message-1999\n"))
        assertEquals(2000, Regex("## User").findAll(markdown).count())
    }

    @Test
    fun `default options preserve the exact protocol body`() {
        val request = body("""{"input":[{"type":"user_input","content":[]},{"type":"thought","signature":"s"}],"tools":[],"stream":true}""")
        assertEquals(request, filterExportRequestBody(request, ExportOptions()))
    }

    @Test
    fun `interactions step filtering excludes thoughts tool results and user input`() {
        val request = body("""{"system_instruction":"s","input":[{"type":"user_input","content":[{"type":"text","text":"question"}]},{"type":"thought","signature":"hidden"},{"type":"function_call","name":"f"},{"type":"function_result","result":[{"type":"text","text":"result"}]},{"type":"model_output","content":[{"type":"text","text":"answer"}]}]}""")
        val filtered = filterExportRequestBody(request, ExportOptions(includeReasoning = false, includeToolCalls = false, assistantOnly = true))
        val steps = filtered.getValue("input").jsonArray
        assertEquals(1, steps.size)
        assertEquals(JsonPrimitive("model_output"), steps.single().jsonObject["type"])
    }

    @Test
    fun `custom body messages cannot reintroduce disabled tools or reasoning`() {
        val request = body("""{"model":"m","tools":[{"type":"function","function":{"name":"lookup"}}],"messages":[{"role":"assistant","content":"answer","reasoning_content":"hidden","tool_calls":[{"id":"call"}]},{"role":"tool","content":"tool result"}]}""")
        val filtered = filterExportRequestBody(request, ExportOptions(includeReasoning = false, includeToolCalls = false))
        assertEquals(1, filtered.getValue("messages").jsonArray.size)
        assertFalse(filtered.toString().contains("hidden"))
        assertFalse(filtered.toString().contains("tool result"))
        assertEquals(request["tools"], filtered["tools"])
    }

    @Test
    fun `assistant-only preserves claude tool results while removing system and user text`() {
        val request = body("""{"system":"system prompt","messages":[{"role":"user","content":[{"type":"text","text":"question"},{"type":"tool_result","tool_use_id":"call","content":"result"}]},{"role":"assistant","content":[{"type":"thinking","thinking":"hidden"},{"type":"text","text":"answer"}]}]}""")
        val filtered = filterExportRequestBody(request, ExportOptions(includeReasoning = false, assistantOnly = true))
        assertFalse(filtered.containsKey("system"))
        assertFalse(filtered.toString().contains("question"))
        assertFalse(filtered.toString().contains("hidden"))
        assertTrue(filtered.toString().contains("result"))
        assertTrue(filtered.toString().contains("answer"))
    }

    @Test
    fun `responses and google reasoning and tool blocks are filtered structurally`() {
        val responses = body("""{"instructions":"system","input":[{"type":"reasoning","encrypted_content":"hidden"},{"type":"function_call","name":"f"},{"type":"function_call_output","output":"result"},{"role":"user","content":"question"},{"role":"assistant","content":[{"type":"output_text","text":"answer"}]}]}""")
        val options = ExportOptions(includeReasoning = false, includeToolCalls = false, assistantOnly = true)
        val filtered = filterExportRequestBody(responses, options)
        assertEquals(1, filtered.getValue("input").jsonArray.size)
        assertFalse(filtered.containsKey("instructions"))
        val google = body("""{"systemInstruction":{"parts":[{"text":"system"}]},"contents":[{"role":"model","parts":[{"thought":true,"text":"hidden"},{"functionCall":{"name":"f"}},{"text":"answer","thoughtSignature":"signature"}]}]}""")
        val result = filterExportRequestBody(google, options)
        assertEquals(JsonObject(mapOf("text" to JsonPrimitive("answer"))), result.getValue("contents").jsonArray.single().jsonObject.getValue("parts").jsonArray.single())
        assertFalse(result.containsKey("systemInstruction"))
    }
}
