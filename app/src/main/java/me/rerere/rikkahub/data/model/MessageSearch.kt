package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ui.markdown.MarkdownSearchTarget
import me.rerere.ui.markdown.parseMarkdown
import me.rerere.ui.markdown.searchSegments
import java.security.MessageDigest
import java.time.Instant
import kotlin.uuid.Uuid

data class SearchTextRange(val start: Int, val end: Int)

data class SearchSnippet(val text: String, val highlight: SearchTextRange)

@Serializable
data class SearchJumpTarget(
    val nodeId: String,
    val messageId: String,
    val partIndex: Int,
    val segmentPath: String,
    val start: Int,
    val end: Int,
    val matchedText: String,
    val contentFingerprint: String,
    val contentLength: Int,
    val query: String,
    val requestId: String = "",
) {
    fun newRequest() = copy(requestId = Uuid.random().toString())

    fun markdownTarget() = MarkdownSearchTarget(segmentPath, start, end, matchedText, requestId)
}

data class MessageSearchHit(
    val conversationId: String,
    val title: String,
    val updateAt: Instant,
    val isUser: Boolean,
    val target: SearchJumpTarget,
    val snippet: SearchSnippet,
) {
    val key: String get() = "${target.messageId}:${target.partIndex}:${target.segmentPath}:${target.start}:${target.end}"
}

fun searchContentFingerprint(content: String): String = buildString(64) {
    MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8)).forEach { byte ->
        val value = byte.toInt() and 0xff
        append("0123456789abcdef"[value ushr 4])
        append("0123456789abcdef"[value and 0xf])
    }
}

fun findSearchRanges(text: String, query: String, checkCancelled: () -> Unit = {}): List<SearchTextRange> {
    if (query.isBlank()) return emptyList()
    return buildList {
        var offset = 0
        while (offset <= text.length - query.length) {
            checkCancelled()
            val start = text.indexOf(query, offset, ignoreCase = true)
            if (start < 0) break
            add(SearchTextRange(start, start + query.length))
            offset = start + query.length
        }
    }
}

/** A short, Unicode-safe window; its selected occurrence is never inferred from marker characters. */
fun buildSearchSnippet(text: String, range: SearchTextRange, contextCharacters: Int = 20): SearchSnippet {
    require(range.start in 0..text.length && range.end in range.start..text.length)
    var start = range.start
    var end = range.end
    repeat(contextCharacters) {
        if (start > 0) start -= Character.charCount(Character.codePointBefore(text, start))
        if (end < text.length) end += Character.charCount(Character.codePointAt(text, end))
    }
    val before = (if (start > 0) "… " else "") + text.substring(start, range.start).replace(SNIPPET_WHITESPACE, " ")
    val matched = text.substring(range.start, range.end).replace(SNIPPET_WHITESPACE, " ")
    val after = text.substring(range.end, end).replace(SNIPPET_WHITESPACE, " ") + if (end < text.length) " …" else ""
    return SearchSnippet(before + matched + after, SearchTextRange(before.length, before.length + matched.length))
}

private val SNIPPET_WHITESPACE = Regex("\\s+")

/** Runs off the main thread. Addresses refer to the exact same displayed document as the renderer. */
fun buildMessageSearchHits(
    conversationId: String,
    nodeId: String,
    message: UIMessage,
    assistant: Assistant?,
    query: String,
    title: String = "",
    updateAt: Instant = Instant.EPOCH,
    fallbackTerms: List<String> = emptyList(),
    checkCancelled: () -> Unit = {},
): List<MessageSearchHit> {
    if (query.isBlank()) return emptyList()
    val scope = if (message.role == MessageRole.USER) AssistantAffectScope.USER else AssistantAffectScope.ASSISTANT
    val documents = message.parts.mapIndexedNotNull { index, part ->
        checkCancelled()
        if (part !is UIMessagePart.Text) return@mapIndexedNotNull null
        val displayed = part.text.replaceRegexes(assistant, scope, visual = true)
        SearchablePart(
            index,
            displayed.length,
            searchContentFingerprint(displayed),
            parseMarkdown(displayed).searchSegments()
        )
    }
    fun collect(terms: List<String>): List<MessageSearchHit> = buildList {
        documents.forEach { (partIndex, contentLength, fingerprint, segments) ->
            segments.forEach { segment ->
                checkCancelled()
                terms.flatMap { findSearchRanges(segment.text, it, checkCancelled) }
                    .distinct()
                    .sortedWith(compareBy<SearchTextRange> { it.start }.thenByDescending { it.end })
                    .fold(mutableListOf<SearchTextRange>()) { ranges, range ->
                        if (ranges.lastOrNull()?.let { range.start < it.end } != true) ranges.add(range)
                        ranges
                    }
                    .forEach { range ->
                        add(
                            MessageSearchHit(
                                conversationId = conversationId,
                                title = title,
                                updateAt = updateAt,
                                isUser = message.role == MessageRole.USER,
                                target = SearchJumpTarget(
                                    nodeId = nodeId,
                                    messageId = message.id.toString(),
                                    partIndex = partIndex,
                                    segmentPath = segment.path,
                                    start = range.start,
                                    end = range.end,
                                    matchedText = segment.text.substring(range.start, range.end),
                                    contentFingerprint = fingerprint,
                                    contentLength = contentLength,
                                    query = query,
                                ),
                                snippet = buildSearchSnippet(segment.text, range),
                            )
                        )
                    }
            }
        }
    }
    return collect(listOf(query)).ifEmpty { collect(fallbackTerms.filter { it.isNotBlank() }.distinct()) }
}

private data class SearchablePart(
    val index: Int,
    val length: Int,
    val fingerprint: String,
    val segments: List<me.rerere.ui.markdown.MarkdownSearchSegment>,
)

/** Native simple_highlight_pos offsets are UTF-8 byte positions, not UTF-16 String indices. */
internal fun decodeFtsMatchPositions(text: String, positions: String): List<SearchTextRange> {
    val rawRanges = positions.split(';').mapNotNull { position ->
        val parts = position.split(',')
        if (parts.size != 2) return@mapNotNull null
        val start = parts[0].toIntOrNull() ?: return@mapNotNull null
        val end = parts[1].toIntOrNull() ?: return@mapNotNull null
        if (start >= 0 && start < end) SearchTextRange(start, end) else null
    }
    if (rawRanges.isEmpty()) return emptyList()
    val wantedOffsets = rawRanges.flatMap { listOf(it.start, it.end) }.toSet()
    val byteOffsets = mutableMapOf(0 to 0)
    var index = 0
    var bytes = 0
    while (index < text.length) {
        val codePoint = Character.codePointAt(text, index)
        bytes += when {
            codePoint <= 0x7f -> 1
            codePoint <= 0x7ff -> 2
            codePoint <= 0xffff -> 3
            else -> 4
        }
        index += Character.charCount(codePoint)
        if (bytes in wantedOffsets) byteOffsets[bytes] = index
    }
    return rawRanges.mapNotNull { range ->
        val start = byteOffsets[range.start] ?: return@mapNotNull null
        val end = byteOffsets[range.end] ?: return@mapNotNull null
        if (start < end) SearchTextRange(start, end) else null
    }
}
