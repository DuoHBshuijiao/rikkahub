package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessagePart

internal data class ConversationCleanupOptions(
    val reasoning: Boolean = false,
    val tools: Boolean = false,
) {
    val isEmpty: Boolean get() = !reasoning && !tools
}

/** 清理所有候选版本；未命中时保留原实例，便于调用方判断是否实际修改。 */
internal fun Conversation.cleanupContent(options: ConversationCleanupOptions): Conversation {
    if (options.isEmpty) return this
    val nodes = messageNodes.mapNotNull { node ->
        val surviving = node.messages.mapIndexedNotNull { index, message ->
            val parts = message.parts.mapNotNull { it.cleanupContent(options) }
            if (parts == message.parts) {
                index to message
            } else if (parts.isEmpty()) {
                null
            } else {
                index to message.copy(parts = parts)
            }
        }
        if (surviving.isEmpty()) return@mapNotNull null
        val messages = surviving.map { it.second }
        if (messages == node.messages) return@mapNotNull node
        val selected = surviving.indexOfFirst { it.first == node.selectIndex }
            .takeIf { it >= 0 }
            ?: surviving.indexOfLast { it.first < node.selectIndex }.coerceAtLeast(0)
        node.copy(messages = messages, selectIndex = selected)
    }
    return if (nodes == messageNodes) this else copy(messageNodes = nodes)
}

@Suppress("DEPRECATION")
private fun UIMessagePart.cleanupContent(options: ConversationCleanupOptions): UIMessagePart? = when {
    options.reasoning && this is UIMessagePart.Reasoning -> null
    options.tools && (this is UIMessagePart.Tool || this is UIMessagePart.ServerTool ||
        this is UIMessagePart.ToolCall || this is UIMessagePart.ToolResult) -> null
    // 工具输出也可能包含结构化思考；仅清理思考时保持工具已执行状态。
    options.reasoning && this is UIMessagePart.Tool -> {
        val cleaned = output.mapNotNull { it.cleanupContent(options) }
        if (cleaned == output) {
            this
        } else {
            copy(output = cleaned.ifEmpty { listOf(UIMessagePart.Text("思考内容已删除")) })
        }
    }
    else -> this
}
