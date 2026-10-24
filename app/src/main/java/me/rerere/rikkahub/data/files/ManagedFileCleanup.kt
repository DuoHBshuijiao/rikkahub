package me.rerere.rikkahub.data.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.repository.FilesRepository
import java.io.File

enum class FileCleanupScope {
    ALL,
    CONVERSATION_ATTACHMENTS,
}

/** Shared deletion policy for explicit removal and conservative automatic recycling. */
internal class ManagedFileCleanup(
    private val repository: FilesRepository,
    private val paths: ManagedFilePaths,
    private val usage: FileUsageChecker,
    private val deleteFile: (File) -> Boolean = { it.delete() },
) {
    private val mutex = Mutex()

    suspend fun delete(entity: ManagedFileEntity, deleteFromDisk: Boolean = true): Boolean = mutex.withLock {
        deleteEntity(entity, deleteFromDisk)
    }

    suspend fun clean(
        folder: String,
        cutoffMillis: Long?,
        scope: FileCleanupScope,
        sync: suspend () -> Unit,
    ): Boolean = mutex.withLock {
        try {
            // Fail before deleting anything when the initial reference snapshot cannot be read.
            if (scope == FileCleanupScope.CONVERSATION_ATTACHMENTS) usage.protectedPaths()
            sync()
            val candidates = repository.listByFolder(folder).first()
                .filter { cutoffMillis == null || it.createdAt < cutoffMillis }
            var allDeleted = true
            for (entity in candidates) {
                currentCoroutineContext().ensureActive()
                val path = paths.relativePath(entity.relativePath)
                if (path == null) {
                    allDeleted = false
                    continue
                }
                if (scope == FileCleanupScope.CONVERSATION_ATTACHMENTS && path in usage.protectedPaths()) continue
                if (!deleteEntity(entity)) allDeleted = false
            }
            allDeleted
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    suspend fun recycle(urls: Collection<String>) = mutex.withLock {
        val candidates = urls.mapNotNull(paths::relativePath).toSet()
        for (path in candidates) {
            currentCoroutineContext().ensureActive()
            if (usage.isReferenced(path)) continue
            val entity = repository.getByPath(path)
            if (entity != null) {
                deleteEntity(entity)
            } else {
                deleteFromDisk(path)
            }
        }
    }

    private suspend fun deleteEntity(entity: ManagedFileEntity, deleteFromDisk: Boolean = true): Boolean {
        val path = paths.relativePath(entity.relativePath) ?: return false
        if (deleteFromDisk && !deleteFromDisk(path)) return false
        repository.deleteById(entity.id)
        return true
    }

    private fun deleteFromDisk(path: String): Boolean = runCatching {
        val file = paths.file(path) ?: return@runCatching false
        !file.exists() || (file.isFile && deleteFile(file))
    }.getOrDefault(false)
}
