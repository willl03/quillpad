package org.qosp.notes.data.sync.core

import org.qosp.notes.data.model.Note

// Sealed class to represent remote operations
sealed class RemoteOperation {
    data class Create(val note: Note, val import: Boolean = false) : RemoteOperation()
    data class Update(val note: Note) : RemoteOperation()
    data class Delete(val note: Note) : RemoteOperation()
}

enum class SyncMethod {
    MAPPING,
    TITLE,
}

data class SyncNote(
    val id: Long,
    val idStr: String,
    val content: String?,
    val title: String,
    val lastModified: Long, // Epoch seconds
    val extra: String? = null,
    val category: String = "",
    val favorite: Boolean? = null,
    val readOnly: Boolean = false,
    /** Path relative to the storage root, e.g. "note.md" or "folder/note.md". */
    val relativePath: String? = null,
    /** Hash of the content, used to detect moves and to reconcile file/local changes. */
    val contentHash: String? = null,
    /** File extension without the dot, e.g. "md" or "txt". */
    val fileExtension: String? = null,
    val size: Long = 0L,
)
