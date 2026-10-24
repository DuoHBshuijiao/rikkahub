package me.rerere.rikkahub.data.files

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.UserPersona
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class ManagedFileCleanupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root by lazy { temporary.newFolder("files") }
    private val paths by lazy { ManagedFilePaths(root) }
    private val dao = MemoryFileDAO()
    private val repository = FilesRepository(dao)
    private val runtime = RuntimeFileReferences()
    private var settings = Settings(assistants = emptyList())
    private var storedMessages = emptyList<String>()
    private var failReferences = false
    private val usage by lazy {
        FileUsageChecker(
            paths,
            settings = {
                check(!failReferences) { "Settings unavailable" }
                settings
            },
            findMessages = { name, encodedName -> storedMessages.filter { name in it || encodedName in it } },
            runtime,
        )
    }
    private fun cleaner(deleteFile: (File) -> Boolean = { it.delete() }) =
        ManagedFileCleanup(repository, paths, usage, deleteFile)

    private suspend fun file(name: String, createdAt: Long = 1): ManagedFileEntity {
        val file = File(root, "upload/$name").apply { requireNotNull(parentFile).mkdirs(); writeText("attachment") }
        return repository.insert(
            ManagedFileEntity(
                folder = "upload", relativePath = "upload/$name", displayName = name,
                mimeType = "image/png", sizeBytes = file.length(), createdAt = createdAt, updatedAt = createdAt,
            )
        )
    }
    private fun ManagedFileEntity.disk() = File(root, relativePath)
    private fun ManagedFileEntity.url() = disk().toURI().toString()
    private fun toolImage(url: String) = UIMessagePart.Tool(
        "call", "image", "{}", listOf(UIMessagePart.Tool("nested", "image", "{}", listOf(UIMessagePart.Image(url))))
    )

    @Test fun `chat cleanup retains every configuration resource including inactive identities and nested presets`() = runTest {
        val legacy = file("legacy.png")
        val identity = file("inactive.png")
        val avatar = file("avatar.png")
        val background = file("background.png")
        val preset = file("preset.png")
        val font = file("font.ttf")
        val attachment = file("attachment.png")
        val orphan = file("orphan.png")
        settings = settings.copy(
            displaySetting = DisplaySetting(userAvatar = Avatar.Image(legacy.url()), chatCustomFontPath = font.relativePath),
            userPersonas = listOf(UserPersona(avatar = Avatar.Image(identity.url()))),
            assistants = listOf(
                Assistant(
                    avatar = Avatar.Image(avatar.url()), background = background.url(),
                    presetMessages = listOf(UIMessage.assistant("").copy(parts = listOf(toolImage(preset.url())))),
                ),
            ),
        )
        // A message reference does not protect an attachment from explicit cleanup.
        storedMessages = listOf(JsonInstant.encodeToString(listOf(UIMessage.user("").copy(
            parts = listOf(UIMessagePart.Image(attachment.url()), UIMessagePart.Image(avatar.url())),
        ))))
        assertTrue(cleaner().clean("upload", null, FileCleanupScope.CONVERSATION_ATTACHMENTS) {})
        listOf(legacy, identity, avatar, background, preset, font).forEach {
            assertTrue(it.disk().isFile)
            assertEquals(it, repository.getById(it.id))
        }
        listOf(attachment, orphan).forEach {
            assertFalse(it.disk().exists())
            assertNull(repository.getById(it.id))
        }
    }

    @Test fun `seven fourteen and thirty day cutoffs use strict creation time boundaries`() = runTest {
        val now = TimeUnit.DAYS.toMillis(100)
        for (days in listOf(7L, 14L, 30L)) {
            val cutoff = now - TimeUnit.DAYS.toMillis(days)
            val old = file("old-$days.png", cutoff - 1)
            val boundary = file("boundary-$days.png", cutoff)
            val recent = file("recent-$days.png", cutoff + 1)
            assertTrue(cleaner().clean("upload", cutoff, FileCleanupScope.CONVERSATION_ATTACHMENTS) {})
            assertFalse(old.disk().exists())
            assertTrue(boundary.disk().isFile)
            assertTrue(recent.disk().isFile)
        }
    }

    @Test fun `all files scope keeps the original ability to remove configuration files`() = runTest {
        val avatar = file("avatar.png")
        settings = settings.copy(assistants = listOf(Assistant(avatar = Avatar.Image(avatar.url()))))
        failReferences = true
        assertTrue(cleaner().clean("upload", null, FileCleanupScope.ALL) {})
        assertFalse(avatar.disk().exists())
        assertNull(repository.getById(avatar.id))
    }

    @Test fun `shared background survives replacement and assistant removal until the final reference is gone`() = runTest {
        val shared = file("shared.png")
        val original = Assistant(background = shared.url())
        val clone = original.copy(id = kotlin.uuid.Uuid.random())
        settings = settings.copy(assistants = listOf(original, clone))
        val cleanup = cleaner()
        settings = settings.copy(assistants = listOf(original.copy(background = null), clone))
        cleanup.recycle(listOf(shared.url()))
        assertTrue(shared.disk().isFile)
        settings = settings.copy(assistants = listOf(clone))
        cleanup.recycle(listOf(shared.url()))
        assertTrue(shared.disk().isFile)
        settings = settings.copy(assistants = emptyList())
        cleanup.recycle(listOf(shared.url()))
        assertFalse(shared.disk().exists())
        assertNull(repository.getById(shared.id))
    }

    @Test fun `automatic recycling protects all persisted alternatives and nested attachments`() = runTest {
        val attachment = file("persisted 图 #%.png")
        storedMessages = listOf(JsonInstant.encodeToString(listOf(
            UIMessage.assistant("").copy(parts = listOf(toolImage(attachment.disk().absolutePath))),
            UIMessage.assistant("selected alternative"),
        )))
        cleaner().recycle(listOf(attachment.url()))
        assertTrue(attachment.disk().isFile)
        storedMessages = listOf(JsonInstant.encodeToString(listOf(
            UIMessage.assistant("").copy(parts = listOf(toolImage(attachment.disk().toURI().toASCIIString()))),
        )))
        cleaner().recycle(listOf(attachment.disk().absolutePath))
        assertTrue(attachment.disk().isFile)
        storedMessages = emptyList()
        cleaner().recycle(listOf(attachment.url()))
        assertFalse(attachment.disk().exists())
    }

    @Test fun `draft queue submitting and generation providers protect cleanup and unregister on disposal`() = runTest {
        val files = listOf(file("draft.png"), file("queue.png"), file("submitting.png"), file("generation.png"))
        var current = files.map { it.url() }.toSet()
        val release = runtime.register { RuntimeFileUsage(current) }
        val cleanup = cleaner()
        assertTrue(cleanup.clean("upload", null, FileCleanupScope.CONVERSATION_ATTACHMENTS) {})
        files.forEach { assertTrue(it.disk().isFile) }
        current = current - files.first().url()
        cleanup.recycle(files.map { it.url() })
        assertFalse(files.first().disk().exists())
        assertTrue(files.last().disk().exists())
        release()
        assertTrue(cleanup.clean("upload", null, FileCleanupScope.CONVERSATION_ATTACHMENTS) {})
        files.forEach { assertFalse(it.disk().exists()) }
    }

    @Test fun `idle live history protects automatic recycling but explicit cleanup may remove it`() = runTest {
        val attachment = file("history.png")
        runtime.register { RuntimeFileUsage(setOf(attachment.url()), inUseUrls = emptySet()) }
        val cleanup = cleaner()
        cleanup.recycle(listOf(attachment.url()))
        assertTrue(attachment.disk().exists())
        assertTrue(cleanup.clean("upload", null, FileCleanupScope.CONVERSATION_ATTACHMENTS) {})
        assertFalse(attachment.disk().exists())
    }

    @Test fun `failed disk deletion retains records and reports partial cleanup failure`() = runTest {
        val failed = file("failed.png")
        val deleted = file("deleted.png")
        val cleanup = cleaner { it.name != "failed.png" && it.delete() }
        assertFalse(cleanup.delete(failed))
        assertEquals(failed, repository.getById(failed.id))
        assertFalse(cleanup.clean("upload", null, FileCleanupScope.ALL) {})
        assertTrue(failed.disk().isFile)
        assertEquals(failed, repository.getById(failed.id))
        assertFalse(deleted.disk().exists())
        cleanup.recycle(listOf(failed.url()))
        assertTrue(failed.disk().isFile)
        assertEquals(failed, repository.getById(failed.id))
    }

    @Test fun `missing files remove stale records but directories are never recursively removed`() = runTest {
        val missing = file("missing.png")
        missing.disk().delete()
        assertTrue(cleaner().delete(missing))
        assertNull(repository.getById(missing.id))
        val directory = file("directory")
        directory.disk().delete()
        directory.disk().mkdir()
        File(directory.disk(), "keep.txt").writeText("keep")
        assertFalse(cleaner().delete(directory))
        assertTrue(File(directory.disk(), "keep.txt").isFile)
        assertEquals(directory, repository.getById(directory.id))
    }

    @Test fun `unavailable references stop safe cleanup and automatic recycling without deleting anything`() = runTest {
        val attachment = file("keep.png")
        failReferences = true
        assertFalse(cleaner().clean("upload", null, FileCleanupScope.CONVERSATION_ATTACHMENTS) {})
        try {
            cleaner().recycle(listOf(attachment.url()))
            error("Expected reference failure")
        } catch (_: IllegalStateException) {
            assertTrue(attachment.disk().isFile)
            assertEquals(attachment, repository.getById(attachment.id))
        }
    }

    @Test fun `new configuration references are rechecked while cleanup is running`() = runTest {
        val first = file("first.png")
        val next = file("next.png")
        val cleanup = cleaner {
            if (it.name == first.disk().name) settings = settings.copy(assistants = listOf(Assistant(background = next.url())))
            it.delete()
        }
        assertTrue(cleanup.clean("upload", null, FileCleanupScope.CONVERSATION_ATTACHMENTS) {})
        assertFalse(first.disk().exists())
        assertTrue(next.disk().isFile)
    }

    @Test fun `synchronization discovers old unregistered files and does not delete later arrivals`() = runTest {
        val first = file("first.png")
        val orphanDisk = File(root, "upload/orphan.png").apply { writeText("unregistered") }
        var arrival: ManagedFileEntity? = null
        val cleanup = cleaner {
            if (it.name == first.disk().name) {
                val disk = File(root, "upload/arrival.png").apply { writeText("new") }
                arrival = first.copy(id = 100, relativePath = "upload/arrival.png", displayName = disk.name)
                dao.files[100] = requireNotNull(arrival)
            }
            it.delete()
        }
        assertTrue(cleanup.clean("upload", null, FileCleanupScope.ALL) {
            repository.insert(first.copy(id = 0, relativePath = "upload/orphan.png", displayName = orphanDisk.name))
        })
        assertFalse(first.disk().exists())
        assertFalse(orphanDisk.exists())
        assertTrue(arrival!!.disk().isFile)
        assertEquals(arrival, repository.getById(100))
    }

    @Test fun `sync failure and paths outside managed storage never delete files`() = runTest {
        val attachment = file("keep.png")
        assertFalse(cleaner().clean("upload", null, FileCleanupScope.ALL) { error("Cannot read directory") })
        assertTrue(attachment.disk().isFile)
        val external = temporary.newFile("external.png")
        cleaner().recycle(listOf(external.toURI().toString(), File(root, "database.db").absolutePath))
        assertTrue(external.isFile)
        assertNull(paths.relativePath(external.absolutePath))
        assertNull(paths.relativePath("upload/../../external.png"))
        assertNull(paths.relativePath("datastore/settings.preferences_pb"))
        assertNull(paths.relativePath(root.absolutePath))
        assertNull(paths.relativePath("file://server/upload/photo.png"))
    }

    @Test fun `a reference added while reading the database protects automatic recycling`() = runTest {
        val attachment = file("pending.png")
        var pending = emptySet<String>()
        runtime.register { RuntimeFileUsage(pending) }
        val checker = FileUsageChecker(paths, { settings }, { _, _ ->
            pending = setOf(attachment.url())
            emptyList()
        }, runtime)
        ManagedFileCleanup(repository, paths, checker).recycle(listOf(attachment.url()))
        assertTrue(attachment.disk().isFile)
        assertEquals(attachment, repository.getById(attachment.id))
    }

    @Test fun `folder synchronization discovers nested files without removing protected descendants`() = runTest {
        val avatar = file("nested/avatar.png")
        val orphan = File(root, "upload/nested/orphan.png").apply { writeText("old unregistered file") }
        settings = settings.copy(assistants = listOf(Assistant(avatar = Avatar.Image(avatar.url()))))
        assertEquals(setOf(avatar.disk(), orphan), paths.scanFolder("upload").toSet())
        assertTrue(cleaner().clean("upload", null, FileCleanupScope.CONVERSATION_ATTACHMENTS) {
            paths.scanFolder("upload").forEach { disk ->
                val relative = requireNotNull(paths.relativePath(disk.path))
                if (repository.getByPath(relative) == null) {
                    repository.insert(avatar.copy(id = 0, relativePath = relative, displayName = disk.name))
                }
            }
        })
        assertTrue(avatar.disk().isFile)
        assertFalse(orphan.exists())
        assertTrue(cleaner().clean("upload", null, FileCleanupScope.ALL) {})
        assertFalse(avatar.disk().exists())
    }

    @Test fun `URI encoded absolute and relative paths resolve to the same managed file`() = runTest {
        val attachment = file("图 #1%.png")
        for (value in listOf(attachment.url(), attachment.disk().absolutePath, attachment.relativePath)) {
            assertEquals(attachment.relativePath, paths.relativePath(value))
        }
        settings = settings.copy(assistants = listOf(Assistant(background = attachment.disk().absolutePath)))
        assertTrue(cleaner().clean("upload", null, FileCleanupScope.CONVERSATION_ATTACHMENTS) {})
        assertTrue(attachment.disk().isFile)
    }
}

private class MemoryFileDAO : ManagedFileDAO {
    val files = linkedMapOf<Long, ManagedFileEntity>()
    private var nextId = 1L
    override suspend fun insert(file: ManagedFileEntity): Long {
        val id = file.id.takeIf { it != 0L } ?: nextId++
        files[id] = file.copy(id = id)
        return id
    }
    override suspend fun update(file: ManagedFileEntity) { files[file.id] = file }
    override suspend fun getById(id: Long) = files[id]
    override suspend fun getByPath(relativePath: String) = files.values.find { it.relativePath == relativePath }
    override fun listByFolder(folder: String): Flow<List<ManagedFileEntity>> = flowOf(files.values.filter { it.folder == folder })
    override suspend fun deleteById(id: Long) = if (files.remove(id) != null) 1 else 0
    override suspend fun deleteByPath(relativePath: String) = getByPath(relativePath)?.let { deleteById(it.id) } ?: 0
    override suspend fun deleteByFolder(folder: String): Int {
        val ids = files.values.filter { it.folder == folder }.map { it.id }
        ids.forEach { files.remove(it) }
        return ids.size
    }
}
