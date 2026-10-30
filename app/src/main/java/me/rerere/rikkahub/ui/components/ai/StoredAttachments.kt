package me.rerere.rikkahub.ui.components.ai

import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.utils.isAllowedFileType
import java.io.File
import java.net.URI

internal data class StoredAttachment(
    val entity: ManagedFileEntity,
    val file: File,
) {
    val key: String = file.canonicalPath
    val isImage: Boolean = entity.mimeType.startsWith("image/", ignoreCase = true)

    fun toMessagePart(): UIMessagePart {
        val url = file.toURI().toASCIIString()
        return if (isImage) {
            UIMessagePart.Image(url)
        } else {
            UIMessagePart.Document(url = url, fileName = entity.displayName, mime = entity.mimeType)
        }
    }
}

/** Read metadata and check disk availability on Dispatchers.IO, never during composition. */
internal fun loadStoredAttachments(
    entities: List<ManagedFileEntity>,
    getFile: (ManagedFileEntity) -> File,
): List<StoredAttachment> = entities.asSequence()
    .filter { entity ->
        val mime = entity.mimeType.lowercase()
        val supported = mime.startsWith("image/") || (
            !mime.startsWith("audio/") && !mime.startsWith("video/") &&
                isAllowedFileType(entity.displayName, mime)
            )
        entity.folder == FileFolders.UPLOAD && supported
    }
    .sortedWith(compareByDescending<ManagedFileEntity> { it.createdAt }.thenByDescending { it.id })
    .map { StoredAttachment(it, getFile(it).canonicalFile) }
    .filter { it.file.isFile }
    .distinctBy { it.key }
    .toList()

internal fun storedAttachmentKeys(parts: List<UIMessagePart>): Set<String> = parts.mapNotNullTo(mutableSetOf()) {
    val url = when (it) {
        is UIMessagePart.Image -> it.url
        is UIMessagePart.Document -> it.url
        is UIMessagePart.Video -> it.url
        is UIMessagePart.Audio -> it.url
        else -> return@mapNotNullTo null
    }
    runCatching {
        val file = if (url.startsWith("file:")) File(URI(url)) else File(url)
        file.takeIf { it.isAbsolute }?.canonicalPath
    }.getOrNull()
}

internal class StoredAttachmentUnavailableException(val keys: Set<String>) :
    Exception("部分附件已被删除或无法读取，请重新选择")

/** Validate the entire selection before returning anything; reuse files without creating or deleting them. */
internal fun prepareStoredAttachments(
    attachments: List<StoredAttachment>,
    selected: Set<String>,
    existingParts: List<UIMessagePart>,
): List<UIMessagePart> {
    val pendingKeys = selected - storedAttachmentKeys(existingParts)
    val pending = attachments.filter { it.key in pendingKeys }.distinctBy { it.key }
    val unavailable = (pendingKeys - pending.map { it.key }.toSet()).toMutableSet()
    pending.forEach { attachment ->
        val readable = runCatching {
            attachment.file.isFile && attachment.file.canRead() && attachment.file.inputStream().use { true }
        }.getOrDefault(false)
        if (!readable) unavailable.add(attachment.key)
    }
    if (unavailable.isNotEmpty()) throw StoredAttachmentUnavailableException(unavailable)
    return pending.map { it.toMessagePart() }
}
