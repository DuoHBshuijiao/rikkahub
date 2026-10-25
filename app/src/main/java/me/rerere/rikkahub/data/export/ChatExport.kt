package me.rerere.rikkahub.data.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.migrateToolMessages
import java.io.File
import java.net.URI
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class ExportOptions(
    val includeReasoning: Boolean = true,
    val expandReasoning: Boolean = false,
    val includeToolCalls: Boolean = true,
    val assistantOnly: Boolean = false,
)

enum class ChatExportFormat(val extension: String, val mimeType: String) {
    MARKDOWN("md", "text/markdown"),
    JSON("json", "application/json"),
}

fun filterExportMessages(messages: List<UIMessage>, options: ExportOptions): List<UIMessage> =
    messages.migrateToolMessages().mapNotNull { message ->
        if (options.assistantOnly && message.role != MessageRole.ASSISTANT) return@mapNotNull null
        if (!options.includeToolCalls && message.role == MessageRole.TOOL) return@mapNotNull null
        val parts = filterExportParts(message.parts, options)
        if (parts.isEmpty()) null else message.copy(parts = parts)
    }

private fun filterExportParts(parts: List<UIMessagePart>, options: ExportOptions): List<UIMessagePart> =
    parts.mapNotNull { part ->
        when (part) {
            is UIMessagePart.Reasoning -> part.takeIf { options.includeReasoning }
            is UIMessagePart.ServerTool -> part.takeIf { options.includeToolCalls }
            is UIMessagePart.Tool -> if (options.includeToolCalls) {
                part.copy(output = filterExportParts(part.output, options))
            } else null
            else -> part
        }
    }

/** A protocol serializer may silently skip an unreadable image; an export must report it instead. */
internal fun validateExportAttachments(messages: List<UIMessage>) {
    fun validate(parts: List<UIMessagePart>) {
        for (part in parts) {
            val url = when (part) {
                is UIMessagePart.Image -> part.url
                is UIMessagePart.Document -> part.url
                is UIMessagePart.Audio -> part.url
                is UIMessagePart.Video -> part.url
                is UIMessagePart.Tool -> { validate(part.output); null }
                else -> null
            }
            if (url?.startsWith("file:") == true) {
                require(runCatching { File(URI(url)).isFile }.getOrDefault(false)) {
                    "本地附件不存在或不可读取：$url"
                }
            }
        }
    }
    messages.forEach { validate(it.parts) }
}

/** Apply exclusions after custom body merging too, only inside protocol message fields. */
fun filterExportRequestBody(body: JsonObject, options: ExportOptions): JsonObject =
    if (options.includeReasoning && options.includeToolCalls && !options.assistantOnly) body else JsonObject(
    body.mapNotNull { (key, value) ->
        when {
            options.assistantOnly && key in SYSTEM_FIELDS -> null
            key in MESSAGE_FIELDS && value is JsonArray -> key to JsonArray(value.mapNotNull { filterWireMessage(it, options) })
            options.assistantOnly && key == "input" && value is JsonPrimitive -> key to JsonArray(emptyList())
            else -> key to value
        }
    }.toMap()
)

private val MESSAGE_FIELDS = setOf("messages", "input", "contents")
private val SYSTEM_FIELDS = setOf("instructions", "system", "system_instruction", "systemInstruction")
private val REASONING_FIELDS = setOf("reasoning_content", "reasoning_details", "reasoning", "thinking", "thoughtSignature", "thought_signature", "encrypted_content")
private val TOOL_FIELDS = setOf("tool_calls", "function_call", "functionCall", "functionResponse")

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.isToolBlock(): Boolean {
    val type = string("type").orEmpty()
    return type in setOf("tool_use", "tool_result", "server_tool_use", "function_call", "function_call_output") ||
        type.endsWith("_call") || type.endsWith("_call_output") || type.endsWith("_result") ||
        containsKey("functionCall") || containsKey("functionResponse")
}

private fun filterWireMessage(value: JsonElement, options: ExportOptions): JsonElement? {
    val message = value as? JsonObject ?: return value.takeUnless { options.assistantOnly }
    val role = message.string("role")
    if (options.assistantOnly && message.string("type") == "user_input") return null
    if (!options.includeToolCalls && role in setOf("tool", "function")) return null
    var filtered = filterWirePart(message, options) as? JsonObject ?: return null
    if (options.assistantOnly && role != null && role !in setOf("assistant", "model", "tool", "function")) {
        // Claude/Google encode the results of assistant tool calls as user messages.
        if (role != "user" || !options.includeToolCalls) return null
        val resultFields = listOf("content", "parts").mapNotNull { key ->
            val parts = (filtered[key] as? JsonArray)?.filter { (it as? JsonObject)?.isToolBlock() == true }.orEmpty()
            if (parts.isEmpty()) null else key to JsonArray(parts)
        }.toMap()
        if (resultFields.isEmpty()) return null
        filtered = JsonObject(filtered.filterKeys { it != "content" && it != "parts" } + resultFields)
    }
    if (listOf("content", "parts").any { (filtered[it] as? JsonArray)?.isEmpty() == true } &&
        filtered.keys.none { it in TOOL_FIELDS } && filtered.string("type") == null) return null
    return filtered
}

private fun filterWirePart(value: JsonElement, options: ExportOptions): JsonElement? = when (value) {
    is JsonArray -> JsonArray(value.mapNotNull { filterWirePart(it, options) })
    is JsonObject -> {
        val reasoning = value.string("type") in setOf("reasoning", "reasoning_text", "summary_text", "thinking", "redacted_thinking", "thought") ||
            (value["thought"] as? JsonPrimitive)?.booleanOrNull == true
        if ((!options.includeReasoning && reasoning) || (!options.includeToolCalls && value.isToolBlock())) {
            null
        } else JsonObject(value.mapNotNull { (key, child) ->
            when {
                !options.includeReasoning && key in REASONING_FIELDS -> null
                !options.includeToolCalls && key in TOOL_FIELDS -> null
                key in setOf("content", "parts", "output", "result") -> filterWirePart(child, options)?.let { key to it }
                else -> key to child
            }
        }.toMap())
    }
    else -> value
}

fun chatExportFileName(title: String, format: ChatExportFormat, time: LocalDateTime = LocalDateTime.now()): String {
    val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim().trim('.').take(80).ifBlank { "chat-export" }
    return "$safeTitle-${time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}.${format.extension}"
}

private val exportJson = Json { prettyPrint = true }

fun exportChatMarkdown(
    title: String,
    messages: List<UIMessage>,
    options: ExportOptions,
    imageUrl: (UIMessagePart.Image) -> String = { it.url },
): String = buildString {
    appendLine("# ${title.replace('\n', ' ').replace('\r', ' ')}")
    appendLine()
    for (message in filterExportMessages(messages, options)) {
        appendLine("## ${message.role.name.lowercase().replaceFirstChar { it.uppercase() }}")
        appendLine()
        appendMarkdownParts(message.parts, imageUrl)
        appendLine("\n---\n")
    }
}

private fun StringBuilder.codeBlock(text: String, language: String) {
    val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
    val fence = "`".repeat(maxOf(3, longest + 1))
    appendLine("$fence$language")
    appendLine(text)
    appendLine(fence)
    appendLine()
}

private fun StringBuilder.appendMarkdownParts(parts: List<UIMessagePart>, imageUrl: (UIMessagePart.Image) -> String, toolOutput: Boolean = false) {
    for (part in parts) {
        when (part) {
            is UIMessagePart.Text -> if (toolOutput) codeBlock(part.text, "text") else appendLine(part.text)
            is UIMessagePart.Reasoning -> {
                appendLine("<details><summary>思考</summary>\n")
                appendLine(part.reasoning)
                appendLine("\n</details>\n")
            }
            is UIMessagePart.Image -> appendLine("![Image](${imageUrl(part)})")
            is UIMessagePart.Document -> appendLine("[${part.fileName.replace("]", "\\]")}](${part.url})")
            is UIMessagePart.Audio -> appendLine("[Audio](${part.url})")
            is UIMessagePart.Video -> appendLine("[Video](${part.url})")
            is UIMessagePart.Tool -> {
                appendLine("### Tool: ${part.toolName.replace('\n', ' ')}")
                appendLine("\nCall ID: ${part.toolCallId}\n")
                appendLine("Input:\n")
                val input = runCatching { exportJson.encodeToString(JsonElement.serializer(), exportJson.parseToJsonElement(part.input)) }.getOrNull()
                codeBlock(input ?: part.input, if (input == null) "text" else "json")
                appendLine("Output${if (part.isExecuted) "" else " (未完成)"}:\n")
                appendMarkdownParts(part.output, imageUrl, toolOutput = true)
            }
            is UIMessagePart.ServerTool -> {
                appendLine("### Server tool: ${part.toolName.replace('\n', ' ')}")
                appendLine("\nCall ID: ${part.toolCallId}; Status: ${part.status}\n")
                part.input?.let { appendLine("Input:\n"); codeBlock(exportJson.encodeToString(JsonElement.serializer(), it), "json") }
                part.output?.let { appendLine("Output:\n"); codeBlock(exportJson.encodeToString(JsonElement.serializer(), it), "json") }
            }
            else -> Unit
        }
        appendLine()
    }
}
