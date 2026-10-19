package org.qosp.notes.data.sync.fs

import java.security.MessageDigest

/**
 * Content hashing for file-backed notes.
 *
 * Line endings and trailing newlines are normalized before hashing: Obsidian, Syncthing and
 * SAF providers do not agree on them, and treating them as edits causes write loops between
 * the app and the folder.
 */
object ContentHash {

    fun of(content: String): String {
        val normalized = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trimEnd('\n')

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))

        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                append(((byte.toInt() shr 4) and 0x0f).toString(16))
                append((byte.toInt() and 0x0f).toString(16))
            }
        }
    }
}
