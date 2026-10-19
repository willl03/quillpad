package org.qosp.notes.data.sync.fs

import android.util.Log
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.core.NoteAction
import org.qosp.notes.data.sync.core.SyncNote
import org.qosp.notes.data.sync.getMapping
import org.qosp.notes.data.sync.toLocalNote
import org.qosp.notes.preferences.CloudService

/**
 * Reconciles the app's notes against the selected storage folder.
 *
 * The folder is authoritative: files that moved or were renamed are followed, files that
 * disappeared delete their local note, and files that changed win over local content. Local
 * notes without a file are left alone (they are never uploaded from here), which is what
 * prevents externally deleted notes from being recreated.
 */
class FileStorageReconciler {

    private val tag = "FileStorageReconciler"

    data class Result(
        val imports: List<NoteAction.Create> = emptyList(),
        val localUpdates: List<NoteAction.Update> = emptyList(),
        val remoteUpdates: List<NoteAction.Update> = emptyList(),
        val remoteDeletes: List<NoteAction.Delete> = emptyList(),
        val newMappings: List<IdMapping> = emptyList(),
        val updatedMappings: List<IdMapping> = emptyList(),
        /** Notes that no longer have a file. They are permanently deleted from the app. */
        val removedLocalNotes: List<Note> = emptyList(),
        /** Notes whose file disappeared but which had local edits we did not want to lose. */
        val keptLocalNotes: List<Note> = emptyList(),
        val removedMappingNoteIds: List<Long> = emptyList(),
    )

    operator fun invoke(
        /** Active (not binned) notes. For file storage this includes local-only notes. */
        localNotes: List<Note>,
        /** Every `.md`/`.txt` file under the selected folder. */
        files: List<SyncNote>,
        mappings: List<IdMapping>,
    ): Result {
        Log.d(
            tag,
            "Reconciling ${localNotes.size} local notes, ${files.size} files, " +
                "${mappings.size} mappings",
        )

        val result = ResultBuilder()
        val localById = localNotes.associateBy { it.id }
        val localHashes = localNotes.associate { it.id to ContentHash.of(it.toStorableContent()) }

        val filesByUri = files.associateBy { it.idStr }
        val filesByPath = files
            .filter { !it.relativePath.isNullOrBlank() }
            .groupBy { normalizePath(it.relativePath!!) }
        val filesByPathLower = files
            .filter { !it.relativePath.isNullOrBlank() }
            .groupBy { normalizePath(it.relativePath!!).lowercase() }
        val filesByHash = files
            .filter { !it.contentHash.isNullOrEmpty() }
            .groupBy { it.contentHash!! }
        val filesByTitle = files.groupBy { it.title.trim().lowercase() }
        val filesByFileName = files.groupBy {
            it.relativePath?.substringAfterLast('/')?.lowercase().orEmpty()
        }

        val claimedFileIds = mutableSetOf<String>()
        val claimedLocalIds = mutableSetOf<Long>()

        // 1. Existing mappings, including mappings for notes that are in the bin.
        for (mapping in mappings) {
            val local = localById[mapping.localNoteId]

            val match = resolveFile(
                mapping = mapping,
                local = local,
                filesByUri = filesByUri,
                filesByPath = filesByPath,
                filesByPathLower = filesByPathLower,
                filesByHash = filesByHash,
                filesByTitle = filesByTitle,
                filesByFileName = filesByFileName,
                claimedFileIds = claimedFileIds,
            )

            when (match) {
                is FileMatch.Found -> {
                    claimedFileIds += match.file.idStr

                    if (local == null) {
                        // The note is in the bin (or gone) but its file is still there. This
                        // finishes an in-app delete that failed to run earlier.
                        result.remoteDeletes += NoteAction.Delete(Note(id = mapping.localNoteId), match.file)
                        continue
                    }

                    claimedLocalIds += local.id
                    reconcileMapped(
                        local = local,
                        localHash = localHashes.getValue(local.id),
                        mapping = mapping,
                        file = match.file,
                        result = result,
                    )
                }

                is FileMatch.Ambiguous -> {
                    // Do not delete anything we cannot identify with certainty. Keep the
                    // mapping and claim the candidates so they are not imported as new notes.
                    claimedFileIds += match.candidates.map { it.idStr }
                    Log.w(tag, "Ambiguous file match for note ${mapping.localNoteId}: ${match.candidates.size} candidates")
                    if (local != null) {
                        claimedLocalIds += local.id
                    }
                }

                FileMatch.NotFound -> {
                    if (local == null) {
                        // Binned note whose file is already gone: nothing left to delete.
                        result.removedMappingNoteIds += mapping.localNoteId
                        continue
                    }

                    claimedLocalIds += local.id
                    val localHash = localHashes.getValue(local.id)
                    val keepLocal = when {
                        // Legacy mappings have no merge base yet. On the first sync after the
                        // upgrade we cannot tell an external delete from an unsynced edit, so
                        // err on the side of keeping the note.
                        mapping.contentHash == null -> true
                        else -> localHash != mapping.contentHash
                    }

                    if (keepLocal) {
                        // The file was deleted/moved, but the app has content that never made
                        // it to disk. Keep the note and detach it instead of losing the edit.
                        Log.w(tag, "Keeping note ${local.id} locally: file is gone and it may have unsynced edits")
                        result.keptLocalNotes += local
                    } else {
                        Log.d(tag, "File for note ${local.id} (${local.title}) was removed; deleting note")
                        result.removedLocalNotes += local
                    }
                    result.removedMappingNoteIds += local.id
                }
            }
        }

        // 2. Files without a mapping. First try to re-link an existing local note (this is
        // also the adoption path when the database was recreated), then import.
        val unmappedLocals = localNotes.filter { it.id !in claimedLocalIds }

        for (file in files) {
            if (file.idStr in claimedFileIds) continue

            val candidate = findUnmappedLocal(
                file = file,
                candidates = unmappedLocals.filter { it.id !in claimedLocalIds },
                localHashes = localHashes,
            )

            if (candidate == null) {
                Log.d(tag, "Importing new file: ${file.relativePath ?: file.title}")
                result.imports += NoteAction.Create(file.toLocalNote(defaultPinned = false), file)
                continue
            }

            claimedLocalIds += candidate.id
            val fileHash = fileHash(file)
            val localHash = localHashes.getValue(candidate.id)
            var mapping = file.getMapping(candidate.id, CloudService.FILE_STORAGE)

            when {
                localHash == fileHash -> {
                    result.newMappings += mapping
                }

                candidate.modifiedDate > file.lastModified && file.lastModified > 0 -> {
                    // Adoption of a note whose local edits never reached disk.
                    result.newMappings += mapping
                    result.remoteUpdates += NoteAction.Update(candidate, file)
                }

                else -> {
                    // Folder wins. Store the file hash as the new base so the next sync does
                    // not re-apply the same update.
                    mapping = mapping.copy(contentHash = fileHash)
                    result.newMappings += mapping
                    result.localUpdates += NoteAction.Update(candidate, file)
                }
            }
        }

        // 3. Local notes with no mapping and no file are intentionally left alone. They are
        // not uploaded; a folder-authoritative app does not recreate externally removed files.

        return result.build()
    }

    private fun reconcileMapped(
        local: Note,
        localHash: String,
        mapping: IdMapping,
        file: SyncNote,
        result: ResultBuilder,
    ) {
        val fileHash = fileHash(file)
        val baseHash = mapping.contentHash
        var newBaseHash = baseHash

        when {
            baseHash == null -> {
                // First sync after the upgrade: the mapping only knows the URI. Use the
                // timestamp once to decide which side is newer, then switch to hashing.
                when {
                    localHash == fileHash -> {
                        newBaseHash = fileHash
                        if (local.title != file.title) {
                            result.localUpdates += NoteAction.Update(local, file)
                        }
                    }

                    local.modifiedDate > file.lastModified && file.lastModified > 0 -> {
                        result.remoteUpdates += NoteAction.Update(local, file)
                        newBaseHash = fileHash
                    }

                    else -> {
                        result.localUpdates += NoteAction.Update(local, file)
                        newBaseHash = fileHash
                    }
                }
            }

            else -> {
                val fileChanged = fileHash != baseHash
                val localChanged = localHash != baseHash

                when {
                    !fileChanged && !localChanged -> {
                        // Pure rename/move: the file content and local content are unchanged,
                        // but the title may have changed with the filename.
                        if (local.title != file.title) {
                            result.localUpdates += NoteAction.Update(local, file)
                        }
                    }

                    fileChanged && !localChanged -> {
                        result.localUpdates += NoteAction.Update(local, file)
                        newBaseHash = fileHash
                    }

                    !fileChanged && localChanged -> {
                        // Local edit queued but not written yet, or a previous write failed.
                        result.remoteUpdates += NoteAction.Update(local, file)
                    }

                    else -> {
                        if (localHash == fileHash) {
                            newBaseHash = fileHash
                        } else {
                            // Both sides changed differently: the folder wins.
                            result.localUpdates += NoteAction.Update(local, file)
                            newBaseHash = fileHash
                        }
                    }
                }
            }
        }

        val updated = mapping.copy(
            storageUri = file.idStr,
            relativePath = file.relativePath,
            contentHash = newBaseHash,
            lastSyncedModified = file.lastModified,
        )
        if (updated != mapping) result.updatedMappings += updated
    }

    private fun findUnmappedLocal(
        file: SyncNote,
        candidates: List<Note>,
        localHashes: Map<Long, String>,
    ): Note? {
        // Prefer content over the filename: a renamed note keeps its content, and the same
        // filename can legitimately belong to a different note.
        val hash = file.contentHash
        if (hash != null) {
            val byHash = candidates.filter { localHashes[it.id] == hash }
            if (byHash.size == 1) return byHash.first()
        }

        val title = file.title.trim().lowercase()
        val byTitle = candidates.filter { it.title.trim().lowercase() == title }
        if (byTitle.size == 1) return byTitle.first()

        return null
    }

    private fun resolveFile(
        mapping: IdMapping,
        local: Note?,
        filesByUri: Map<String, SyncNote>,
        filesByPath: Map<String, List<SyncNote>>,
        filesByPathLower: Map<String, List<SyncNote>>,
        filesByHash: Map<String, List<SyncNote>>,
        filesByTitle: Map<String, List<SyncNote>>,
        filesByFileName: Map<String, List<SyncNote>>,
        claimedFileIds: Set<String>,
    ): FileMatch {
        mapping.storageUri?.let { uri ->
            filesByUri[uri]?.let { file ->
                if (file.idStr !in claimedFileIds) return FileMatch.Found(file)
            }
        }

        val pathMatch = mapping.relativePath?.let { path ->
            val normalized = normalizePath(path)
            select(filesByPath[normalized], claimedFileIds)
                ?: select(filesByPathLower[normalized.lowercase()], claimedFileIds)
        }

        val hashMatch = mapping.contentHash?.let { hash ->
            select(filesByHash[hash], claimedFileIds)
        }

        // A unique content match is stronger evidence than a path match: if a file was moved
        // out of the way and a different file took its old path, the path now points at the
        // wrong note. Keep the path match only while it still has the expected content.
        if (hashMatch is FileMatch.Found &&
            (pathMatch == null || (pathMatch as? FileMatch.Found)?.file?.contentHash != mapping.contentHash)
        ) {
            return hashMatch
        }
        if (pathMatch != null) return pathMatch
        if (hashMatch != null) return hashMatch

        val title = local?.title?.trim()?.lowercase()
        if (!title.isNullOrEmpty()) {
            select(filesByTitle[title], claimedFileIds)?.let { return it }
        }

        val fileName = fileNameFromUri(mapping.storageUri)?.lowercase()
        if (!fileName.isNullOrEmpty()) {
            select(filesByFileName[fileName], claimedFileIds)?.let { return it }
        }

        return FileMatch.NotFound
    }

    private fun select(candidates: List<SyncNote>?, claimedFileIds: Set<String>): FileMatch? {
        if (candidates.isNullOrEmpty()) return null
        val available = candidates.filter { it.idStr !in claimedFileIds }
        return when (available.size) {
            0 -> null
            1 -> FileMatch.Found(available.first())
            else -> FileMatch.Ambiguous(available)
        }
    }

    private fun fileHash(file: SyncNote): String =
        file.contentHash ?: ContentHash.of(file.content ?: "")

    private fun fileNameFromUri(uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        // Avoid android.net.Uri here so the reconciler stays unit-testable on the JVM.
        // SAF document IDs encode the path separator as %2F; that is all we need for a
        // filename fallback.
        val decoded = uri
            .replace("%2F", "/", ignoreCase = true)
            .replace("%20", " ", ignoreCase = true)
        return decoded.substringAfterLast('/').substringAfterLast(':').takeIf { it.isNotBlank() }
    }

    private fun normalizePath(path: String): String =
        path.replace('\\', '/').trim('/')

    private sealed interface FileMatch {
        data class Found(val file: SyncNote) : FileMatch
        data class Ambiguous(val candidates: List<SyncNote>) : FileMatch
        data object NotFound : FileMatch
    }

    private class ResultBuilder {
        val imports = mutableListOf<NoteAction.Create>()
        val localUpdates = mutableListOf<NoteAction.Update>()
        val remoteUpdates = mutableListOf<NoteAction.Update>()
        val remoteDeletes = mutableListOf<NoteAction.Delete>()
        val newMappings = mutableListOf<IdMapping>()
        val updatedMappings = mutableListOf<IdMapping>()
        val removedLocalNotes = mutableListOf<Note>()
        val keptLocalNotes = mutableListOf<Note>()
        val removedMappingNoteIds = mutableListOf<Long>()

        fun build() = Result(
            imports = imports,
            localUpdates = localUpdates,
            remoteUpdates = remoteUpdates,
            remoteDeletes = remoteDeletes,
            newMappings = newMappings,
            updatedMappings = updatedMappings,
            removedLocalNotes = removedLocalNotes,
            keptLocalNotes = keptLocalNotes,
            removedMappingNoteIds = removedMappingNoteIds.distinct(),
        )
    }
}
