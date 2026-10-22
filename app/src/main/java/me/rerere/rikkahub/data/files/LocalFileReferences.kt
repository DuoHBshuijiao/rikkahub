package me.rerere.rikkahub.data.files

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.net.URI

/** Rebuild stored app-private file references after a package, Android user or device change. */
internal class LocalFileReferences(filesDir: File) {
    private val root = filesDir.canonicalFile

    fun rebuild(value: String): String {
        val isUri = value.startsWith("file:")
        val path = if (isUri) {
            val uri = runCatching { URI(value) }.getOrNull() ?: return value
            if (!uri.authority.isNullOrEmpty() || uri.query != null || uri.fragment != null) return value
            uri.path ?: return value
        } else {
            value
        }
        val relative = PRIVATE_FILES.matchEntire(path)?.groupValues?.get(1) ?: return value
        if (relative.substringBefore('/') !in FILE_FOLDERS) return value
        if ('\\' in relative || '\u0000' in relative || relative.split('/').any { it == "." || it == ".." }) {
            return value
        }
        val target = runCatching { File(root, relative).canonicalFile }.getOrNull() ?: return value
        if (!target.path.startsWith(root.path + File.separator)) return value
        // Do not require the file to exist: backups may restore settings, database and files separately.
        return if (isUri) "file://${target.toURI().rawPath}" else target.path
    }

    /** Only resource fields are rewritten. Message text, prompts and tool arguments remain verbatim. */
    fun rebuildJson(value: String): String {
        val original = runCatching { Json.parseToJsonElement(value) }.getOrNull() ?: return value
        val updated = rebuildElement(original)
        return if (updated == original) value else updated.toString()
    }

    private fun rebuildElement(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map(::rebuildElement))
        is JsonObject -> {
            val type = (element["type"] as? JsonPrimitive)?.contentOrNull
                ?.substringAfterLast('.')?.substringAfterLast('$')?.lowercase()
            JsonObject(element.mapValues { (key, child) ->
                when {
                    key in VERBATIM_FIELDS -> child
                    key == "url" && type in MEDIA_TYPES -> rebuildString(child)
                    key == "background" && "id" in element -> rebuildString(child)
                    else -> rebuildElement(child)
                }
            })
        }
        else -> element
    }

    private fun rebuildString(element: JsonElement): JsonElement {
        if (element !is JsonPrimitive || !element.isString) return element
        val updated = rebuild(element.content)
        return if (updated == element.content) element else JsonPrimitive(updated)
    }

    companion object {
        private val PRIVATE_FILES = Regex(
            "^/data/(?:user(?:_de)?/[0-9]+|data)/[A-Za-z0-9_.]+/files/(.+)$"
        )
        private val FILE_FOLDERS = setOf("upload", "images", "fonts", "skills", "media_creation")
        private val MEDIA_TYPES = setOf("image", "video", "audio", "document")
        private val VERBATIM_FIELDS = setOf("text", "reasoning", "input", "arguments", "metadata")
    }
}
