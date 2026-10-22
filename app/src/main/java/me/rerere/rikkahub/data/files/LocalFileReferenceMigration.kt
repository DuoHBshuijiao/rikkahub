package me.rerere.rikkahub.data.files

import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/** Runs before any conversation sessions are loaded, including on the staged backup database. */
internal object LocalFileReferenceMigration {
    fun rebuildDatabase(database: SupportSQLiteDatabase, filesDir: File) {
        val root = filesDir.canonicalPath
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS local_file_reference_state (id INTEGER PRIMARY KEY, files_dir TEXT NOT NULL)"
        )
        val previous = database.query("SELECT files_dir FROM local_file_reference_state WHERE id = 1").use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        if (previous == root) return

        val references = LocalFileReferences(filesDir)
        database.beginTransaction()
        try {
            rebuildColumn(database, "message_node", "messages", references::rebuildJson)
            rebuildColumn(database, "ConversationEntity", "nodes", references::rebuildJson)
            rebuildColumn(database, "favorites", "snapshot_json", references::rebuildJson)
            rebuildColumn(database, "GenMediaEntity", "path", references::rebuild)
            rebuildColumn(database, "GenMediaEntity", "source_paths") { value ->
                value.split('\n').joinToString("\n", transform = references::rebuild)
            }
            database.execSQL(
                "INSERT OR REPLACE INTO local_file_reference_state (id, files_dir) VALUES (1, ?)",
                arrayOf(root),
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun rebuildColumn(
        database: SupportSQLiteDatabase,
        table: String,
        column: String,
        transform: (String) -> String,
    ) {
        // Work in bounded batches, closing each cursor before updating the same table.
        var after = Long.MIN_VALUE
        while (true) {
            var lastId = after
            val changes = database.query(
                "SELECT rowid, `$column` FROM `$table` WHERE rowid > ? ORDER BY rowid LIMIT 20",
                arrayOf<Any>(after),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        lastId = cursor.getLong(0)
                        if (cursor.isNull(1)) continue
                        val original = cursor.getString(1)
                        val updated = transform(original)
                        if (updated != original) add(lastId to updated)
                    }
                }
            }
            if (lastId == after) return
            for ((id, updated) in changes) {
                database.execSQL("UPDATE `$table` SET `$column` = ? WHERE rowid = ?", arrayOf<Any>(updated, id))
            }
            after = lastId
        }
    }
}
