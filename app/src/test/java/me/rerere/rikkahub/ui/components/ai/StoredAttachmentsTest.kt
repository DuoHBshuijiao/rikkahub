package me.rerere.rikkahub.ui.components.ai

import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.FileFolders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StoredAttachmentsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun entity(
        id: Long,
        name: String,
        mime: String,
        createdAt: Long = id,
        folder: String = FileFolders.UPLOAD,
    ) = ManagedFileEntity(
        id = id,
        folder = folder,
        relativePath = "$folder/$name",
        displayName = name,
        mimeType = mime,
        sizeBytes = 10,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun file(entity: ManagedFileEntity): File = File(temporary.root, entity.relativePath)

    private fun save(entity: ManagedFileEntity): ManagedFileEntity = entity.also {
        file(it).apply {
            parentFile?.mkdirs()
            writeText("attachment")
        }
    }

    @Test
    fun `library only includes existing upload images and supported documents sorted newest first`() {
        val entities = listOf(
            save(entity(1, "photo.png", "image/png")),
            save(entity(2, "notes.md", "application/octet-stream")),
            save(entity(3, "report.pdf", "application/pdf")),
            save(entity(4, "office.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
            save(entity(5, "clip.mp4", "video/mp4")),
            save(entity(6, "recording.mp3", "audio/mpeg")),
            save(entity(7, "archive.zip", "application/zip")),
            entity(8, "missing.png", "image/png"),
            save(entity(9, "generated.png", "image/png", folder = FileFolders.MEDIA_CREATION)),
            save(entity(10, "misnamed.txt", "audio/mpeg")),
        )
        assertEquals(listOf(4L, 3L, 2L, 1L), loadStoredAttachments(entities, ::file).map { it.entity.id })
    }

    @Test
    fun `canonical paths deduplicate aliases without merging different files sharing a display name`() {
        val original = save(entity(1, "one.png", "image/png"))
        val alias = original.copy(id = 3, relativePath = "upload/../upload/one.png", createdAt = 3)
        val other = save(entity(2, "two.png", "image/png")).copy(displayName = original.displayName)
        assertEquals(listOf(3L, 2L), loadStoredAttachments(listOf(original, other, alias), ::file).map { it.entity.id })
    }

    @Test
    fun `mixed selections retain list order metadata and original files without copies`() {
        val image = save(entity(3, "图片 #%.png", "image/png"))
        val document = save(entity(2, "文档 with spaces.pdf", "application/pdf"))
        val attachments = loadStoredAttachments(listOf(document, image), ::file)
        val before = temporary.root.walkTopDown().filter { it.isFile }.associate { it.path to it.readBytes().toList() }
        val parts = prepareStoredAttachments(attachments, attachments.reversed().map { it.key }.toSet(), emptyList())
        assertEquals(
            listOf(
                UIMessagePart.Image(file(image).toURI().toASCIIString()),
                UIMessagePart.Document(file(document).toURI().toASCIIString(), document.displayName, document.mimeType),
            ),
            parts,
        )
        assertEquals(before, temporary.root.walkTopDown().filter { it.isFile }.associate {
            it.path to
                it.readBytes().toList()
        })
    }

    @Test
    fun `already added attachments match encoded URIs and absolute paths`() {
        val image = save(entity(2, "图 #%.png", "image/png"))
        val document = save(entity(1, "notes.md", "text/markdown"))
        val attachments = loadStoredAttachments(listOf(document, image), ::file)
        val existing = listOf(
            UIMessagePart.Image(file(image).toURI().toASCIIString()),
            UIMessagePart.Document(file(document).absolutePath, document.displayName, document.mimeType),
            UIMessagePart.Image("https://example.com/image.png"),
        )
        assertEquals(attachments.map { it.key }.toSet(), storedAttachmentKeys(existing))
        assertTrue(prepareStoredAttachments(attachments, attachments.map { it.key }.toSet(), existing).isEmpty())
    }

    @Test
    fun `deleted selection aborts the whole batch and retains the other source file`() {
        val image = save(entity(2, "photo.png", "image/png"))
        val document = save(entity(1, "notes.txt", "text/plain"))
        val attachments = loadStoredAttachments(listOf(image, document), ::file)
        assertTrue(file(image).delete())
        try {
            prepareStoredAttachments(attachments, attachments.map { it.key }.toSet(), emptyList())
            throw AssertionError("Expected an unavailable selection")
        } catch (e: StoredAttachmentUnavailableException) {
            assertEquals(setOf(file(image).canonicalPath), e.keys)
        }
        assertTrue(file(document).isFile)
        assertEquals("attachment", file(document).readText())
        assertFalse(file(image).exists())
    }

    @Test
    fun `selection removed from the observed library must be reselected`() {
        val entity = save(entity(1, "photo.png", "image/png"))
        val key = file(entity).canonicalPath
        try {
            prepareStoredAttachments(emptyList(), setOf(key), emptyList())
            throw AssertionError("Expected an unavailable selection")
        } catch (e: StoredAttachmentUnavailableException) {
            assertEquals(setOf(key), e.keys)
        }
        assertTrue(file(entity).isFile)
    }
}
