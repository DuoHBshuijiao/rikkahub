package me.rerere.rikkahub.data.files

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class LocalFileReferenceMigrationTest {
    private lateinit var directory: File
    private lateinit var database: SQLiteDatabase
    private val oldUri = "file:///data/user/0/me.rerere.rikkahub/files/upload/photo.png"
    private val messages get() = """[{"parts":[{"type":"image","url":"$oldUri"}]}]"""

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = Files.createTempDirectory(context.cacheDir.toPath(), "file-reference-test-").toFile()
        database = SQLiteDatabase.openDatabase(
            SQLiteConfiguration.configure(
                context,
                SQLiteDatabaseConfiguration(File(directory, "database").path, SQLiteDatabase.CREATE_IF_NECESSARY),
            ),
            null,
            null,
        )
        database.execSQL("CREATE TABLE message_node (messages TEXT)")
        database.execSQL("CREATE TABLE ConversationEntity (nodes TEXT)")
        database.execSQL("CREATE TABLE favorites (snapshot_json TEXT)")
        database.execSQL("CREATE TABLE GenMediaEntity (path TEXT, source_paths TEXT)")
    }

    @After fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    @Test fun rebuildsMultipleBatchesAndAllStoredResourceColumns() {
        repeat(105) { database.execSQL("INSERT INTO message_node VALUES (?)", arrayOf(messages)) }
        database.execSQL("INSERT INTO ConversationEntity VALUES (?)", arrayOf(messages))
        database.execSQL("INSERT INTO favorites VALUES (?)", arrayOf(messages))
        database.execSQL("INSERT INTO GenMediaEntity VALUES (?, ?)", arrayOf(oldUri, "$oldUri\n$oldUri"))
        val filesDir = File(directory, "files")
        LocalFileReferenceMigration.rebuildDatabase(database, filesDir)
        val rebuilt = LocalFileReferences(filesDir)
        assertEquals(
            105L,
            database.longForQuery(
                "SELECT count(*) FROM message_node WHERE messages = ?",
                arrayOf(rebuilt.rebuildJson(messages))
            )
        )
        assertEquals(rebuilt.rebuildJson(messages), value("ConversationEntity", "nodes"))
        assertEquals(rebuilt.rebuildJson(messages), value("favorites", "snapshot_json"))
        assertEquals(rebuilt.rebuild(oldUri), value("GenMediaEntity", "path"))
        assertEquals("${rebuilt.rebuild(oldUri)}\n${rebuilt.rebuild(oldUri)}", value("GenMediaEntity", "source_paths"))
        assertEquals(filesDir.canonicalPath, value("local_file_reference_state", "files_dir"))
        LocalFileReferenceMigration.rebuildDatabase(database, filesDir)
        assertEquals(105L, database.longForQuery("SELECT count(*) FROM message_node", null))
    }

    @Test fun failedMigrationRollsBackEarlierRowsAndCanBeRetried() {
        database.execSQL("INSERT INTO message_node VALUES (?)", arrayOf(messages))
        database.execSQL("DROP TABLE favorites")
        val filesDir = File(directory, "files")
        assertThrows(Exception::class.java) { LocalFileReferenceMigration.rebuildDatabase(database, filesDir) }
        assertEquals(messages, value("message_node", "messages"))
        assertEquals(0L, database.longForQuery("SELECT count(*) FROM local_file_reference_state", null))

        database.execSQL("CREATE TABLE favorites (snapshot_json TEXT)")
        LocalFileReferenceMigration.rebuildDatabase(database, filesDir)
        assertEquals(LocalFileReferences(filesDir).rebuildJson(messages), value("message_node", "messages"))
    }

    private fun value(table: String, column: String): String =
        database.stringForQuery("SELECT `$column` FROM `$table` LIMIT 1", null)
}
