package org.qosp.notes.data.sync

import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.core.SyncNote
import org.qosp.notes.data.sync.nextcloud.NextcloudNote
import org.qosp.notes.preferences.CloudService
import java.time.Instant

fun NextcloudNote.asSyncNote() = SyncNote(
    id = id,
    idStr = id.toString(),
    content = content,
    title = title,
    lastModified = modified, // Nextcloud already uses epoch seconds.
    extra = etag,
    category = category,
    favorite = favorite,
    readOnly = readOnly == true,
)

// Convert SyncNote to local Note with full content
fun SyncNote.toLocalNote(defaultPinned: Boolean) = Note(
    id = 0L, // Will be assigned by a database
    title = title,
    content = content ?: "",
    isPinned = favorite ?: defaultPinned,
    modifiedDate = lastModified.takeIf { it > 0 } ?: Instant.now().epochSecond,
    notebookId = null, // TODO: Handle category to notebook conversion if needed
    isMarkdownEnabled = fileExtension?.equals("md", ignoreCase = true) ?: true
)

fun SyncNote.updateLocalNote(localNote: Note) = localNote.copy(
    title = title,
    content = content ?: "",
    isPinned = favorite ?: localNote.isPinned,
    modifiedDate = lastModified.takeIf { it > 0 } ?: localNote.modifiedDate,
    // Keep the editor mode in step with the actual file. Otherwise an externally renamed
    // .txt file would be renamed back to .md on the next edit.
    isMarkdownEnabled = fileExtension?.equals("md", ignoreCase = true)
        ?: localNote.isMarkdownEnabled,
)

fun SyncNote.getMapping(noteId: Long, service: CloudService) = IdMapping(
    localNoteId = noteId,
    remoteNoteId = id,
    provider = service,
    extras = extra,
    isDeletedLocally = false,
    storageUri = idStr,
    relativePath = relativePath,
    contentHash = contentHash,
    lastSyncedModified = lastModified,
)
