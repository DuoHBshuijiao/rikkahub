package me.rerere.rikkahub.data.db.fts

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.SearchTextRange
import me.rerere.rikkahub.data.model.decodeFtsMatchPositions
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

data class MessageSearchResult(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val title: String,
    val updateAt: Instant,
    val snippet: String,
)

enum class MessageSearchSort(val orderBy: String) {
    RELEVANCE("rank, update_at DESC"),
    NEWEST_FIRST("update_at DESC, rank"),
    OLDEST_FIRST("update_at ASC, rank"),
}

data class MessageSearchCandidate(
    val result: MessageSearchResult,
    val assistantId: String,
    val matchedTerms: List<String>,
)

private const val TAG = "MessageFtsManager"

class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase
    private val indexMutex = Mutex()
    private var supportsPositions = true

    suspend fun indexConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        indexMutex.withLock {
            database.withTransaction { indexStoredConversation(conversation.id.toString()) }
        }
    }

    // Always read the persisted version while holding the index lock. A queued update must not
    // overwrite a rebuild with an older Conversation snapshot.
    private suspend fun indexStoredConversation(conversationId: String) {
        val conversation = database.conversationDao().getConversationById(conversationId)
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
        if (conversation == null) return
        var offset = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val nodes = database.messageNodeDao().getNodesOfConversationPaged(conversationId, 20, offset)
            if (nodes.isEmpty()) break
            nodes.forEach { node ->
                JsonInstant.decodeFromString<List<UIMessage>>(node.messages).forEach { message ->
                    val text = message.extractFtsText()
                    if (text.isNotBlank()) {
                        db.execSQL(
                            "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?)",
                            arrayOf(
                                text,
                                node.id,
                                message.id.toString(),
                                conversationId,
                                conversation.title,
                                conversation.updateAt.toString(),
                            )
                        )
                    }
                }
            }
            offset += nodes.size
        }
    }

    suspend fun updateTitle(conversationId: String, title: String) = withContext(Dispatchers.IO) {
        indexMutex.withLock {
            db.execSQL("UPDATE message_fts SET title = ? WHERE conversation_id = ?", arrayOf(title, conversationId))
        }
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        indexMutex.withLock {
            db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
        }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        indexMutex.withLock {
            db.execSQL("DELETE FROM message_fts")
            db.execSQL("DROP TABLE IF EXISTS message_search_metadata")
        }
    }

    suspend fun ensureIndex(
        force: Boolean = false,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        indexMutex.withLock {
            db.execSQL("CREATE TABLE IF NOT EXISTS message_search_metadata (version INTEGER NOT NULL)")
            val version = db.query("SELECT version FROM message_search_metadata LIMIT 1").use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
            if (!force && version == 1) return@withLock
            db.execSQL("DELETE FROM message_search_metadata")
            val ids = database.conversationDao().getAllIds()
            onProgress(0, ids.size)
            db.execSQL("DELETE FROM message_fts")
            ids.forEachIndexed { index, id ->
                currentCoroutineContext().ensureActive()
                database.withTransaction { indexStoredConversation(id) }
                onProgress(index + 1, ids.size)
            }
            db.execSQL("INSERT INTO message_search_metadata(version) VALUES (1)")
        }
    }

    suspend fun search(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        // 只搜这些助手的对话，null 表示不限
        assistantIds: Collection<String>? = null,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        ensureIndex()
        indexMutex.withLock {
            val results = mutableListOf<MessageSearchResult>()
            if (assistantIds != null && assistantIds.isEmpty()) return@withContext results
            val matchQuery = buildFtsMatchQuery(keyword, ::simpleQuery) ?: return@withContext results
            val assistantFilter = if (assistantIds != null) {
                """
            AND EXISTS (
                SELECT 1 FROM conversationentity AS conversation
                WHERE conversation.id = message_fts.conversation_id
                  AND conversation.assistant_id IN (${assistantIds.joinToString { "?" }})
            )
                """.trimIndent()
            } else {
                ""
            }
            val cursor = db.query(
                """
            SELECT node_id, message_id, conversation_id, title, update_at,
                   simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet
            FROM message_fts
            WHERE text MATCH ?
            $assistantFilter
            ORDER BY ${sort.orderBy}
            LIMIT 50
                """.trimIndent(),
                arrayOf(matchQuery, *assistantIds.orEmpty().toTypedArray())
            )
            Log.i(TAG, "search: $keyword")
            cursor.use {
                while (it.moveToNext()) {
                    results.add(
                        MessageSearchResult(
                            nodeId = it.getString(0),
                            messageId = it.getString(1),
                            conversationId = it.getString(2),
                            title = it.getString(3),
                            updateAt = Instant.ofEpochMilli(it.getLong(4)),
                            snippet = it.getString(5),
                        )
                    )
                }
            }
            results
        }
    }

    /** UI-only occurrence search; legacy snippets and the Web/tool result contract stay unchanged. */
    suspend fun searchCandidates(
        keyword: String,
        sort: MessageSearchSort,
        assistantIds: Collection<String>?,
    ): List<MessageSearchCandidate> = withContext(Dispatchers.IO) {
        ensureIndex()
        indexMutex.withLock {
            if (assistantIds != null && assistantIds.isEmpty()) return@withLock emptyList()
            val query = buildFtsMatchQuery(keyword, ::simpleQuery) ?: return@withLock emptyList()
            val filter = if (assistantIds == null) {
                ""
            } else {
                "AND conversation.assistant_id IN (${assistantIds.joinToString { "?" }})"
            }
            val open = "\uE000${Uuid.random()}\uE001"
            val close = "\uE002${Uuid.random()}\uE003"
            fun readCandidates(positions: Boolean): List<MessageSearchCandidate> {
                val highlight = if (positions) {
                    "simple_highlight_pos(message_fts, 0)"
                } else {
                    "simple_highlight(message_fts, 0, ?, ?)"
                }
                val arguments = buildList<String> {
                    if (!positions) addAll(listOf(open, close))
                    add(query)
                    addAll(assistantIds.orEmpty())
                }
                return db.query(
                    """
                    SELECT node_id, message_id, conversation_id, message_fts.title, update_at,
                           message_fts.text, $highlight, conversation.assistant_id
                    FROM message_fts
                    JOIN conversationentity AS conversation ON conversation.id = message_fts.conversation_id
                    WHERE message_fts.text MATCH ? $filter
                    ORDER BY ${sort.orderBy.replace(
                        "rank",
                        "message_fts.rank"
                    ).replace("update_at", "message_fts.update_at")}, message_id
                    LIMIT 50
                    """.trimIndent(),
                    arguments.toTypedArray(),
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val text = cursor.getString(5)
                            val ranges = if (positions) {
                                decodeFtsMatchPositions(text, cursor.getString(6).orEmpty())
                            } else {
                                decodeHighlightedRanges(text, cursor.getString(6).orEmpty(), open, close)
                            }
                            add(
                                MessageSearchCandidate(
                                    result = MessageSearchResult(
                                        nodeId = cursor.getString(0),
                                        messageId = cursor.getString(1),
                                        conversationId = cursor.getString(2),
                                        title = cursor.getString(3),
                                        updateAt = Instant.ofEpochMilli(cursor.getLong(4)),
                                        snippet = "",
                                    ),
                                    assistantId = cursor.getString(7),
                                    matchedTerms = ranges.map { text.substring(it.start, it.end) }.distinct(),
                                )
                            )
                        }
                    }
                }
            }
            try {
                readCandidates(supportsPositions)
            } catch (e: Exception) {
                if (!supportsPositions || e.message?.contains("no such function", ignoreCase = true) != true) throw e
                supportsPositions = false
                readCandidates(false)
            }
        }
    }

    // simple 扩展生成的查询，带拼音展开和前缀匹配
    private fun simpleQuery(text: String): String =
        db.query(
            "SELECT simple_query(?)",
            arrayOf(text)
        ).use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" }
}

private val QUERY_RUN = Regex("[A-Za-z0-9]+|[^\\sA-Za-z0-9]+")

// 中文片段各字之间最多允许夹几个额外的字（如标点、“的”），再大就容易把散落各处的字也算作命中
private const val CJK_NEAR_SLACK = 2

/**
 * 把搜索词拼成 FTS5 查询，各段之间用 AND 连接：
 * 英文和数字交给 [asciiQuery]（simple_query，支持拼音和前缀匹配）；
 * 索引按单字切分中文，所以中文等其他字符的连续片段拆成单字，用 NEAR 要求它们挨得足够近，
 * 效果接近按词匹配，又不需要 jieba 词典。
 * 没有可搜索的内容时返回 null。
 */
internal fun buildFtsMatchQuery(keyword: String, asciiQuery: (String) -> String): String? {
    val terms = QUERY_RUN.findAll(keyword).mapNotNull { match ->
        val text = match.value
        val term = if (text.first().isAsciiLetterOrDigit()) asciiQuery(text) else nearQuery(text)
        term.takeIf { it.isNotBlank() }
    }.toList()
    return terms.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
}

private fun nearQuery(text: String): String {
    val chars = text.codePoints().toArray().map { "\"${String(Character.toChars(it)).replace("\"", "\"\"")}\"" }
    if (chars.size == 1) return chars.single()
    return "NEAR(${chars.joinToString(" ")}, ${chars.size - 2 + CJK_NEAR_SLACK})"
}

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private fun UIMessage.extractFtsText(): String =
    parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }

internal fun decodeHighlightedRanges(text: String, highlighted: String, open: String, close: String): List<SearchTextRange> {
    require(open !in text && close !in text)
    val restored = StringBuilder()
    val ranges = mutableListOf<SearchTextRange>()
    var cursor = 0
    while (cursor < highlighted.length) {
        val start = highlighted.indexOf(open, cursor)
        if (start < 0) {
            restored.append(highlighted, cursor, highlighted.length)
            break
        }
        restored.append(highlighted, cursor, start)
        val end = highlighted.indexOf(close, start + open.length)
        require(end >= 0)
        val rangeStart = restored.length
        restored.append(highlighted, start + open.length, end)
        ranges += SearchTextRange(rangeStart, restored.length)
        cursor = end + close.length
    }
    require(restored.toString() == text)
    return ranges
}
