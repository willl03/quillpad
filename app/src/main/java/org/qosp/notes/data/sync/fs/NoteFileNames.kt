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

        return cleaned.ifBlank { FALLBACK }
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
}
