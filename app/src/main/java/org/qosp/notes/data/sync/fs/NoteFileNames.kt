package org.qosp.notes.data.sync.fs

/**
 * Pure filename rules for file-backed notes.
 *
 * The storage folder is shared with Obsidian and sync tools, so generated filenames must
 * never look hidden (leading dot), contain a sync-conflict marker, or contain characters
 * that SAF providers reject.
 */
object NoteFileNames {

    const val FALLBACK = "Untitled"

    /** Leave room for the extension and a collision suffix under the common 255-byte limit. */
    private const val MAX_FILENAME_BYTES = 180

    private val invalidCharacters = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")
    private val syncConflictMarker = Regex("sync-conflict", RegexOption.IGNORE_CASE)

    fun sanitize(raw: String): String {
        val cleaned = raw
            .trim()
            .replace(invalidCharacters, " ")
            .replace(syncConflictMarker, "sync conflict")
            .trim()
            .trimStart('.')
            .trimEnd('.')

        return truncateUtf8(cleaned.ifBlank { FALLBACK }, MAX_FILENAME_BYTES)
    }

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value

        val result = StringBuilder(value.length)
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val chars = String(Character.toChars(codePoint))
            val size = chars.toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes) break
            result.append(chars)
            bytes += size
            index += Character.charCount(codePoint)
        }
        return result.toString().ifBlank { FALLBACK }
    }

    fun isNoteFile(name: String): Boolean {
        if (name.startsWith(".")) return false
        if (name.contains("sync-conflict", ignoreCase = true)) return false
        return name.endsWith(".md", ignoreCase = true) || name.endsWith(".txt", ignoreCase = true)
    }

    fun isIgnoredDirectory(name: String): Boolean {
        return name.startsWith(".") || name.contains("sync-conflict", ignoreCase = true)
    }

    fun titleFromName(name: String): String {
        return if (name.contains('.')) name.substringBeforeLast('.') else name
    }

    fun extension(name: String): String = name.substringAfterLast('.', "md")

    fun normalizePath(path: String): String = path.replace('\\', '/').trim('/')

    /**
     * Extracts a display filename from a SAF URI. Document ids encode path separators and
     * non-ASCII characters, so decode them first. Kept free of android.net.Uri so the
     * reconciler remains unit-testable on the JVM.
     */
    fun fileNameFromUri(uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        val decoded = runCatching {
            java.net.URLDecoder.decode(uri.replace("+", "%2B"), Charsets.UTF_8.name())
        }.getOrDefault(uri)
        return decoded.substringAfterLast('/').substringAfterLast(':').takeIf { it.isNotBlank() }
    }
}
