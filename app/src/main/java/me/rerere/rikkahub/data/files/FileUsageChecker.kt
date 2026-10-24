package me.rerere.rikkahub.data.files

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.localFileUrls
import me.rerere.rikkahub.utils.JsonInstant

internal class FileUsageChecker(
    private val paths: ManagedFilePaths,
    private val settings: suspend () -> Settings,
    private val findMessages: suspend (String, String) -> List<String>,
    private val runtime: RuntimeFileReferences,
) {
    suspend fun protectedPaths(includeAllRuntime: Boolean = false): Set<String> {
        val configured = settings().configuredFileUrls()
        val live = runtime.snapshot()
        return (configured + if (includeAllRuntime) live.referencedUrls else live.inUseUrls)
            .mapNotNullTo(mutableSetOf(), paths::relativePath)
    }

    suspend fun isReferenced(relativePath: String): Boolean {
        if (relativePath in protectedPaths(includeAllRuntime = true)) return true
        val file = requireNotNull(paths.file(relativePath))
        // Search a small candidate set, then check structured attachments by normalized path.
        // The two spellings cover both decoded paths and percent-encoded file URIs.
        val name = JsonInstant.encodeToString(file.name).removeSurrounding("\"")
        val encodedName = file.toURI().toASCIIString().substringAfterLast('/')
        val messages = findMessages(name, encodedName)
        if (messages.any { json ->
                JsonInstant.decodeFromString<List<UIMessage>>(json).flatMap { it.parts }.localFileUrls()
                    .any { paths.relativePath(it) == relativePath }
            }) {
            return true
        }
        // Database reads suspend; settings and queues may have changed in the meantime.
        return relativePath in protectedPaths(includeAllRuntime = true)
    }
}

internal fun Settings.configuredFileUrls(): Set<String> = buildSet {
    fun avatar(value: Avatar) {
        if (value is Avatar.Image) add(value.url)
    }
    avatar(displaySetting.userAvatar)
    userPersonas.forEach { avatar(it.avatar) }
    assistants.forEach { assistant ->
        avatar(assistant.avatar)
        assistant.background?.let(::add)
        addAll(assistant.presetMessages.flatMap { it.parts }.localFileUrls())
    }
    displaySetting.chatCustomFontPath.takeIf { it.isNotBlank() }?.let(::add)
}
