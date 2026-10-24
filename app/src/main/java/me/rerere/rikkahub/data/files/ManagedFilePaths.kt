package me.rerere.rikkahub.data.files

import java.io.File
import java.io.IOException
import java.net.URI

/** Compare resource references by their actual app-private location, rather than URI spelling. */
internal class ManagedFilePaths(filesDir: File) {
    private val root = filesDir.canonicalFile

    fun relativePath(value: String): String? = runCatching {
        if ('\u0000' in value || value.isBlank()) return@runCatching null
        val input = if (value.startsWith("file:")) {
            val uri = URI(value)
            if (!uri.authority.isNullOrEmpty() || uri.query != null || uri.fragment != null) return@runCatching null
            File(uri)
        } else {
            File(value)
        }
        val file = (if (input.isAbsolute) input else File(root, value)).canonicalFile
        if (!file.path.startsWith(root.path + File.separator)) return@runCatching null
        val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
        if (relative.substringBefore('/') !in folders || '/' !in relative) return@runCatching null
        relative
    }.getOrNull()

    fun file(value: String): File? = relativePath(value)?.let { File(root, it) }

    fun scanFolder(folder: String): List<File> {
        require(relativePath("$folder/.scan") == "$folder/.scan") { "Invalid managed folder" }
        val directory = File(root, folder)
        if (!directory.exists()) return emptyList()
        if (!directory.isDirectory) throw IOException("Cannot read managed folder: $folder")
        // Never descend through symbolic links, including links to another managed directory.
        return directory.walkTopDown()
            .onEnter { it.canonicalFile == it.absoluteFile }
            .onFail { _, error -> throw error }
            .filter { it.isFile && it.canonicalFile == it.absoluteFile }
            .toList()
    }

    companion object {
        private val folders = setOf(
            FileFolders.UPLOAD,
            "images",
            FileFolders.FONTS,
            FileFolders.TOOL_OUTPUTS,
            FileFolders.MEDIA_CREATION,
        )
    }
}
