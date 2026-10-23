package me.rerere.rikkahub.data.db.fts

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class MessageFtsManagerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var database: AppDatabase
    private lateinit var fts: MessageFtsManager
    private val assistantId = Uuid.random()

    @Before
    fun setUp() {
        directory = Files.createTempDirectory(context.cacheDir.toPath(), "message-fts-test-").toFile()
        open()
    }

    @After
    fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    private fun open() {
        database = AppDatabaseFactory.create(context, File(directory, "db").path)
        fts = MessageFtsManager(database)
    }

    @Test
    fun savingOnlyRewritesChangedMessages() = runBlocking {
        val apple = UIMessage.user("apple pie")
        val banana = UIMessage.assistant("banana split")
        val cherry = UIMessage.user("cherry tart")
        val conversation = conversation("first", apple, banana, cherry)
        save(conversation, insert = true)
        fts.ensureIndex()
        val before = indexedRowids()

        val durian = banana.copy(parts = UIMessage.assistant("durian cake").parts)
        val elderberry = UIMessage.assistant("elderberry jam")
        save(
            conversation.copy(
                title = "renamed",
                messageNodes = conversation.messageNodes.take(1) +
                    MessageNode.of(durian).copy(id = conversation.messageNodes[1].id) +
                    MessageNode.of(elderberry),
            ),
            insert = false,
        )
        val after = indexedRowids()

        assertEquals(before[apple.id.toString()], after[apple.id.toString()])
        assertNotEquals(before[banana.id.toString()], after[banana.id.toString()])
        assertEquals(setOf(apple.id, banana.id, elderberry.id).map { it.toString() }.toSet(), after.keys)
        assertRowidTableMatches()
        assertEquals(emptyList<String>(), fts.search("banana").map { it.messageId })
        assertEquals(emptyList<String>(), fts.search("cherry").map { it.messageId })
        val result = fts.search("durian").single()
        assertEquals(banana.id.toString(), result.messageId)
        assertEquals("renamed", result.title)
    }

    @Test
    fun deletingConversationKeepsOthers() = runBlocking {
        val kept = conversation("kept", UIMessage.user("shared word kept"))
        val deleted = conversation("deleted", UIMessage.user("shared word deleted"))
        save(kept, insert = true)
        save(deleted, insert = true)
        fts.ensureIndex()

        database.conversationDao().delete(database.conversationDao().getConversationById(deleted.id.toString())!!)
        fts.deleteConversation(deleted.id.toString())

        assertEquals(listOf(kept.id.toString()), fts.search("shared").map { it.conversationId })
        assertRowidTableMatches()
    }

    @Test
    fun existingIndexGetsRowidTableOnOpen() = runBlocking {
        val first = conversation("first", UIMessage.user("legacy apple"))
        val second = conversation("second", UIMessage.user("legacy banana"))
        save(first, insert = true)
        save(second, insert = true)
        fts.ensureIndex()
        database.openHelper.writableDatabase.execSQL("DROP TABLE message_fts_rowid")
        database.close()

        open()
        assertRowidTableMatches()
        database.conversationDao().delete(database.conversationDao().getConversationById(first.id.toString())!!)
        fts.deleteConversation(first.id.toString())
        assertEquals(listOf(second.id.toString()), fts.search("legacy").map { it.conversationId })
        assertRowidTableMatches()
    }

    @Test
    fun rebuildAndClearKeepRowidMappingConsistent() = runBlocking {
        val first = conversation("first", UIMessage.user("shared apple"))
        val second = conversation("second", UIMessage.user("shared banana"))
        save(first, insert = true)
        save(second, insert = true)
        fts.ensureIndex()
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO message_fts_rowid(fts_rowid, conversation_id) VALUES (1000000, ?)",
            arrayOf(first.id.toString()),
        )

        fts.ensureIndex(force = true)
        assertEquals(2, indexedRowids().size)
        assertRowidTableMatches()

        fts.deleteAll()
        assertEquals(emptyMap<String, Long>(), indexedRowids())
        assertRowidTableMatches()
        assertEquals(2, fts.search("shared").size)
        assertRowidTableMatches()
    }

    private fun conversation(title: String, vararg messages: UIMessage) = Conversation(
        assistantId = assistantId,
        title = title,
        messageNodes = messages.map { MessageNode.of(it) },
    )

    private suspend fun save(conversation: Conversation, insert: Boolean) {
        val entity = ConversationEntity(
            id = conversation.id.toString(),
            assistantId = conversation.assistantId.toString(),
            title = conversation.title,
            nodes = "[]",
            createAt = conversation.createAt.toEpochMilli(),
            updateAt = conversation.updateAt.toEpochMilli(),
            chatSuggestions = "[]",
            isPinned = false,
        )
        database.withTransaction {
            if (insert) database.conversationDao().insert(entity) else database.conversationDao().update(entity)
            database.messageNodeDao().deleteByConversation(entity.id)
            database.messageNodeDao().insertAll(
                conversation.messageNodes.mapIndexed { index, node ->
                    MessageNodeEntity(
                        id = node.id.toString(),
                        conversationId = entity.id,
                        nodeIndex = index,
                        messages = JsonInstant.encodeToString(node.messages),
                        selectIndex = node.selectIndex,
                    )
                }
            )
        }
        fts.indexConversation(conversation)
    }

    private fun indexedRowids(): Map<String, Long> =
        database.openHelper.writableDatabase.query("SELECT message_id, rowid FROM message_fts").use {
            buildMap { while (it.moveToNext()) put(it.getString(0), it.getLong(1)) }
        }

    private fun assertRowidTableMatches() {
        val query = "SELECT rowid, conversation_id FROM message_fts ORDER BY rowid"
        val mapped = "SELECT fts_rowid, conversation_id FROM message_fts_rowid ORDER BY fts_rowid"
        assertEquals(rows(query), rows(mapped))
    }

    private fun rows(sql: String): List<Pair<Long, String>> =
        database.openHelper.writableDatabase.query(sql).use {
            buildList { while (it.moveToNext()) add(it.getLong(0) to it.getString(1)) }
        }
}
