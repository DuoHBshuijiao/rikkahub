package me.rerere.rikkahub.data.db.fts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class MessageOccurrenceSearchTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "occurrence-search-${Uuid.random()}"
    private val conversationId = Uuid.random()
    private val assistantId = Uuid.random().toString()
    private val nodeId = Uuid.random().toString()
    private lateinit var database: AppDatabase
    private lateinit var manager: MessageFtsManager

    @Before
    fun setUp() {
        database = AppDatabaseFactory.create(context, databaseName)
        manager = MessageFtsManager(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    private suspend fun seed(vararg texts: String): List<UIMessage> {
        database.conversationDao().insert(
            ConversationEntity(
                id = conversationId.toString(),
                assistantId = assistantId,
                title = "创作记录",
                nodes = "[]",
                createAt = 0,
                updateAt = 0,
                chatSuggestions = "[]",
                isPinned = false,
            )
        )
        val messages = texts.map { UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(it))) }
        database.messageNodeDao().insert(
            MessageNodeEntity(nodeId, conversationId.toString(), 0, JsonInstant.encodeToString(messages), 0)
        )
        return messages
    }

    @Test
    fun firstSearchRebuildsLegacyTruncatedIndexAndReturnsNativeChinesePositions() = runBlocking {
        val text = "正文".repeat(15_000) + "窗前明月😀"
        seed(text)
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf(text.take(10_000), nodeId, "legacy", conversationId.toString(), "旧索引", "0"),
        )
        val progress = mutableListOf<Pair<Int, Int>>()
        manager.ensureIndex { current, total -> progress.add(current to total) }
        val candidates = manager.searchCandidates("明月", MessageSearchSort.RELEVANCE, listOf(assistantId))
        assertEquals(1, candidates.size)
        assertTrue(candidates.single().matchedTerms.any { "明月" in it })
        assertEquals(1 to 1, progress.last())
        assertTrue(
            manager.searchCandidates("明月", MessageSearchSort.NEWEST_FIRST, listOf(Uuid.random().toString())).isEmpty()
        )
        assertTrue(manager.searchCandidates("明月", MessageSearchSort.OLDEST_FIRST, null).isNotEmpty())
    }

    @Test
    fun allMessageVersionsKeepTheirOwnMessageId() = runBlocking {
        val messages = seed("第一版窗前明月", "第二版山中明月")
        val candidates = manager.searchCandidates("明月", MessageSearchSort.RELEVANCE, null)
        assertEquals(messages.map { it.id.toString() }.toSet(), candidates.map { it.result.messageId }.toSet())
    }

    @Test
    fun queuedIndexUpdateReadsStoredMessageInsteadOfStaleConversationSnapshot() = runBlocking {
        seed("最新正文关键词")
        manager.indexConversation(
            Conversation(id = conversationId, assistantId = Uuid.parse(assistantId), messageNodes = emptyList())
        )
        assertEquals(1, manager.searchCandidates("关键词", MessageSearchSort.RELEVANCE, null).size)
        val entity = database.conversationDao().getConversationById(conversationId.toString())!!
        database.conversationDao().delete(entity)
        manager.indexConversation(
            Conversation(id = conversationId, assistantId = Uuid.parse(assistantId), messageNodes = emptyList())
        )
        database.openHelper.writableDatabase.query("SELECT count(*) FROM message_fts").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
    }
}
