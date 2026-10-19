package org.qosp.notes.data.repo

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.msoul.datastore.defaultOf
import org.qosp.notes.data.dao.IdMappingDao
import org.qosp.notes.data.dao.NoteDao
import org.qosp.notes.data.dao.ReminderDao
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.NoteEntity
import org.qosp.notes.data.sync.core.AvailabilityStatus
import org.qosp.notes.data.sync.core.BackendProvider
import org.qosp.notes.data.sync.core.BaseResult
import org.qosp.notes.data.sync.core.GenericError
import org.qosp.notes.data.sync.core.ISyncBackend
import org.qosp.notes.data.sync.core.NoteAction
import org.qosp.notes.data.sync.core.ProcessRemoteActions
import org.qosp.notes.data.sync.core.RemoteOperation.Create
import org.qosp.notes.data.sync.core.RemoteOperation.Delete
import org.qosp.notes.data.sync.core.RemoteOperation.Update
import org.qosp.notes.data.sync.core.Success
import org.qosp.notes.data.sync.core.SyncMethod
import org.qosp.notes.data.sync.core.SyncNote
import org.qosp.notes.data.sync.core.SyncNotesResult
import org.qosp.notes.data.sync.core.SynchronizeNotes
import org.qosp.notes.data.sync.fs.FileStorageReconciler
import org.qosp.notes.data.sync.getMapping
import org.qosp.notes.data.sync.toLocalNote
import org.qosp.notes.data.sync.updateLocalNote
import org.qosp.notes.di.SyncScope
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.SortMethod
import org.qosp.notes.ui.utils.Toaster
import java.time.Instant

class NoteRepositoryImpl(
    private val noteDao: NoteDao,
    private val idMappingDao: IdMappingDao,
    private val reminderDao: ReminderDao,
    private val backendProvider: BackendProvider,
    private val synchronizeNotes: SynchronizeNotes,
    private val fileStorageReconciler: FileStorageReconciler,
    private val processRemoteActions: ProcessRemoteActions,
    private val syncingScope: SyncScope,
    private val toaster: Toaster
) : NoteRepository {

    private val tag = NoteRepositoryImpl::class.java.simpleName
    private val syncMutex = Mutex()

    private companion object {
        const val EMPTY_FOLDER_MESSAGE =
            "Storage folder appears empty or unavailable; skipping deletions"
    }

    private suspend fun cleanMappingsForLocalNotes(vararg notes: Note) {
        val n = notes.filter { it.isLocalOnly }
        Log.d(tag, "cleanMappingsForLocalNotes: Cleaning ${n.size} local-only notes from ${notes.size} total")
        idMappingDao.setNotesToBeDeleted(*n.map { it.id }.toLongArray())
    }

    override suspend fun syncNotes(): BaseResult {
        Log.d(tag, "syncNotes: Starting synchronization")

        val syncProvider = backendProvider.syncProvider.value
        if (syncProvider == null || !backendProvider.isSyncing) {
            Log.i(tag, "syncNotes: Sync not available or disabled")
            return Success
        }

        // Check backend availability before proceeding
        when (val status = syncProvider.isAvailable()) {
            is AvailabilityStatus.Available -> {
                Log.d(tag, "syncNotes: Backend is available")
            }

            is AvailabilityStatus.Unavailable -> {
                Log.w(tag, "syncNotes: Backend unavailable - ${status.reason}")
                toaster.showLong(status.reason)
                return GenericError(status.reason)
            }
        }

        return syncMutex.withLock {
            try {
                val allLocalNotes = getAll().first()

                // Get all remote notes and convert to metadata
                val allRemoteNotes =
                    syncProvider.getAll() ?: return@withLock GenericError("Failed to fetch remote notes")

                if (syncProvider.type == CloudService.FILE_STORAGE) {
                    if (!syncFileStorage(allLocalNotes, allRemoteNotes, syncProvider)) {
                        return@withLock GenericError(EMPTY_FOLDER_MESSAGE)
                    }
                } else {
                    syncRemoteProvider(allLocalNotes, allRemoteNotes, syncProvider)
                }
                Log.i(tag, "syncNotes: Synchronization completed successfully")
            } catch (e: Exception) {
                Log.e(tag, "syncNotes: Synchronization failed: ${e.message}", e)
                return@withLock GenericError(e.message ?: "Unknown error")
            }
            Success
        }
    }

    /**
     * File storage is folder-authoritative: the file listing decides which notes exist.
     * Nothing is uploaded from here; app-created notes are written by [processRemoteActions]
     * when they are created or edited.
     */
    private suspend fun syncFileStorage(
        allLocalNotes: List<Note>,
        files: List<SyncNote>,
        syncProvider: ISyncBackend,
    ): Boolean {
        val mappingCount = idMappingDao.getCountByCloudService(CloudService.FILE_STORAGE)

        // An empty listing is much more likely to be an unmounted SD card / lost permission
        // than the user deleting every file. Never wipe the local index in that case.
        if (files.isEmpty() && mappingCount > 0) {
            Log.w(tag, "syncFileStorage: $EMPTY_FOLDER_MESSAGE")
            toaster.showLong(EMPTY_FOLDER_MESSAGE)
            return false
        }

        val localNotes = allLocalNotes.filterNot { it.isDeleted }
        val mappings = idMappingDao.getAllByCloudService(CloudService.FILE_STORAGE)

        Log.d(
            tag,
            "syncFileStorage: Found ${files.size} files, ${localNotes.size} local notes, " +
                "$mappingCount mappings",
        )

        val result = fileStorageReconciler(
            localNotes = localNotes,
            files = files,
            mappings = mappings,
        )

        Log.d(
            tag,
            "syncFileStorage: ${result.imports.size} imports, ${result.localUpdates.size} local updates, " +
                "${result.remoteUpdates.size} writes, ${result.remoteDeletes.size} file deletes, " +
                "${result.removedLocalNotes.size} permanent note deletes",
        )

        applyFileStorageResult(result, syncProvider)
        return true
    }

    private suspend fun applyFileStorageResult(
        result: FileStorageReconciler.Result,
        syncProvider: ISyncBackend,
    ) {
        // Notes whose file disappeared are gone for good: remove the note, its reminders and
        // its mapping. This is intentionally not the in-app bin: the folder is authoritative.
        for (note in result.removedLocalNotes) {
            try {
                reminderDao.deleteIfNoteIdIn(listOf(note.id))
                noteDao.delete(note.toEntity())
                idMappingDao.deleteByLocalId(note.id)
                Log.d(tag, "applyFileStorageResult: Removed note ${note.id} (${note.title})")
            } catch (e: Exception) {
                Log.e(tag, "applyFileStorageResult: Failed to remove note ${note.id}", e)
            }
        }

        // Imports from new files and updates from changed files.
        applyLocalUpdates(result.imports + result.localUpdates, syncProvider)

        // Adoption/rename/move mapping updates.
        result.newMappings.forEach { upsertMapping(it) }
        result.updatedMappings.forEach { idMappingDao.update(it) }

        // Finish in-app deletes whose file was still present, and write local edits.
        result.remoteDeletes.forEach { processRemoteActions(it.note.id, Delete(it.note)) }
        result.remoteUpdates.forEach { processRemoteActions(it.note.id, Update(it.note)) }

        // Retry notes that were never written to the folder (interrupted create, backend
        // unavailable). Kept-local notes are marked so they are not uploaded again.
        if (result.keptLocalNotes.isNotEmpty()) {
            noteDao.markLocalOnly(result.keptLocalNotes.map { it.id })
        }
        result.pendingUploads.forEach { processRemoteActions(it.id, Create(it)) }

        if (result.removedMappingNoteIds.isNotEmpty()) {
            // These are file-storage mappings that must be detached (kept-local notes or
            // stale rows). Do not remove another provider's mapping for the same note.
            idMappingDao.deleteByLocalIdsAndProvider(
                result.removedMappingNoteIds,
                CloudService.FILE_STORAGE,
            )
        }

        if (result.removedLocalNotes.isNotEmpty()) {
            toaster.showShort(
                "${result.removedLocalNotes.size} notes removed because their files were " +
                    "deleted or moved out of the folder"
            )
        }

        if (result.keptLocalNotes.isNotEmpty()) {
            Log.w(
                tag,
                "applyFileStorageResult: Kept ${result.keptLocalNotes.size} notes locally because " +
                    "their files disappeared but they had unsynced edits",
            )
            toaster.showShort(
                "${result.keptLocalNotes.size} notes kept locally because their files disappeared " +
                    "but they had unsaved changes"
            )
        }
    }

    private suspend fun upsertMapping(mapping: IdMapping) {
        val provider = mapping.provider ?: return
        val existing = idMappingDao.getByLocalIdAndProvider(mapping.localNoteId, provider)
            ?: idMappingDao.getNonRemoteByLocalId(mapping.localNoteId)

        if (existing == null) {
            idMappingDao.insert(mapping)
        } else {
            idMappingDao.update(
                existing.copy(
                    remoteNoteId = mapping.remoteNoteId,
                    provider = mapping.provider,
                    extras = mapping.extras,
                    isDeletedLocally = false,
                    storageUri = mapping.storageUri,
                    relativePath = mapping.relativePath,
                    contentHash = mapping.contentHash,
                    lastSyncedModified = mapping.lastSyncedModified,
                )
            )
        }
    }

    private suspend fun syncRemoteProvider(
        allLocalNotes: List<Note>,
        allRemoteNotes: List<SyncNote>,
        syncProvider: ISyncBackend,
    ) {
        val syncMethod =
            if (idMappingDao.getCountByCloudService(syncProvider.type) == 0)
                SyncMethod.TITLE else SyncMethod.MAPPING

        // Get all local notes (excluding local-only ones)
        val localNotes = allLocalNotes.filterNot { it.isLocalOnly || it.isDeleted }

        Log.d(
            tag, "syncNotes: Syncing by $syncMethod. " +
                "Found ${allRemoteNotes.size} remote notes, and ${localNotes.size} local notes"
        )

        // Use SynchronizeNotes to determine what updates are needed
        val syncResult =
            synchronizeNotes(localNotes, allRemoteNotes, service = syncProvider.type, syncMethod)
        Log.d(
            tag, "sync updates: ${syncResult.localUpdates.size} local, " +
                "${syncResult.remoteUpdates.size} remote"
        )

        if (syncMethod == SyncMethod.TITLE) applyMappingChanges(syncResult, syncProvider) // Initial import
        applyLocalUpdates(syncResult.localUpdates, syncProvider)
        applyRemoteUpdates(syncResult.remoteUpdates)
    }

    private suspend fun applyLocalUpdates(localUpdates: List<NoteAction>, syncProvider: ISyncBackend) {
        for (action in localUpdates) {
            try {
                when (action) {
                    is NoteAction.Create -> {
                        val syncNote = action.remoteNote
                        val noteId = insertNote(syncNote.toLocalNote(defaultPinned = false), sync = false)
                        idMappingDao.insert(syncNote.getMapping(noteId, syncProvider.type))
                    }

                    is NoteAction.Update -> {
                        val mergedNote = action.remoteNote.updateLocalNote(action.note)
                        val note = if (action.note.isList) {
                            val tasks = mergedNote.mdToTaskList(mergedNote.content)
                            mergedNote.copy(content = "", taskList = tasks, isList = true)
                        } else {
                            mergedNote
                        }
                        idMappingDao.updateNoteExtras(
                            localId = action.note.id,
                            cloudService = syncProvider.type,
                            extras = action.remoteNote.extra
                        )
                        updateNote(note, sync = false)
                    }

                    is NoteAction.Delete -> {
                        moveNotesToBin(action.note, sync = false)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "applyLocalUpdates: Failed to apply action $action: ${e.message}")
            }
        }
    }

    private fun applyRemoteUpdates(remoteUpdates: List<NoteAction>) {
        for (action in remoteUpdates) {
            try {
                when (action) {
                    is NoteAction.Create -> processRemoteActions(action.note.id, Create(action.note))
                    is NoteAction.Update -> processRemoteActions(action.note.id, Update(action.note))
                    is NoteAction.Delete -> processRemoteActions(action.note.id, Delete(action.note))
                }
            } catch (e: Exception) {
                Log.e(tag, "applyRemoteUpdates: Failed to apply action $action: ${e.message}")
            }
        }
    }

    private suspend fun applyMappingChanges(syncResult: SyncNotesResult, syncProvider: ISyncBackend) {
        syncResult.newMappings.forEach { idMappingDao.insert(it) }
        syncResult.localUpdates.filterIsInstance<NoteAction.Update>().map {
            it.remoteNote.getMapping(it.note.id, syncProvider.type)
        }.forEach { idMappingDao.insert(it) }
        syncResult.remoteUpdates.filterIsInstance<NoteAction.Update>().map {
            it.remoteNote.getMapping(it.note.id, syncProvider.type)
        }.forEach { idMappingDao.insert(it) }
    }

    override suspend fun insertNote(note: Note, sync: Boolean): Long {
        Log.d(tag, "insertNote: Creating note '${note.title}', isLocalOnly=${note.isLocalOnly}")
        val noteId = noteDao.insert(note.toEntity())
        if (note.isLocalOnly.not() && backendProvider.isSyncing && sync) {
            val note1 = note.copy(id = noteId)
            processRemoteActions(note1.id, Create(note1))
        }
        return noteId
    }

    override suspend fun updateNotes(vararg notes: Note, sync: Boolean) = notes.forEach { updateNote(it, sync) }

    private suspend fun updateNote(note: Note, sync: Boolean) {
        Log.d(tag, "updateNote: Updating note ID=${note.id}, title='${note.title}'")
        noteDao.update(note.toEntity())
        if (note.isLocalOnly.not() && backendProvider.isSyncing && sync) {
            processRemoteActions(note.id, Update(note))
        }
    }

    override suspend fun moveNotesToBin(vararg notes: Note, sync: Boolean) {
        Log.d(tag, "moveNotesToBin: Moving ${notes.size} notes to bin")
        val entities = notes.map { it.toEntity().copy(isDeleted = true, deletionDate = Instant.now().epochSecond) }
            .toTypedArray<NoteEntity>()

        noteDao.update(*entities)
        reminderDao.deleteIfNoteIdIn(notes.map { it.id })
        cleanMappingsForLocalNotes(*notes)
        notes.filterNot { it.isLocalOnly }.forEach {
            if (sync) processRemoteActions(it.id, Delete(it))
        }
    }

    override suspend fun restoreNotes(vararg notes: Note) {
        Log.d(tag, "restoreNotes: Restoring ${notes.size} notes from bin")
        val array = notes
            .map { it.toEntity().copy(isDeleted = false, deletionDate = null) }
            .toTypedArray()
        noteDao.update(*array)
        cleanMappingsForLocalNotes(*notes)
        if (backendProvider.isSyncing) {
            backendProvider.syncProvider.value?.let { syncProvider ->
                syncingScope.launch {
                    val syncableNotes = notes.filterNot { it.isLocalOnly }
                    Log.d(tag, "restoreNotes: Re-syncing ${syncableNotes.size} restored notes to ${syncProvider.type}")

                    if (syncProvider.type == CloudService.FILE_STORAGE) {
                        restoreFileStorageNotes(syncableNotes, syncProvider)
                    } else {
                        try {
                            syncableNotes
                                .associateWith { syncProvider.createNote(it) }
                                .forEach { (n, syncNote) ->
                                    idMappingDao.insert(syncNote.getMapping(n.id, syncProvider.type))
                                    noteDao.updateLastModified(n.id, syncNote.lastModified)
                                }
                        } catch (e: Exception) {
                            Log.e(tag, "restoreNotes: Error re-syncing restored notes", e)
                        }
                    }
                }
            }
        }
    }

    /**
     * Restoring a file-backed note must not blindly create a second file. If the note's file
     * still exists, re-link it; only create when there really is nothing on disk.
     */
    private suspend fun restoreFileStorageNotes(notes: List<Note>, syncProvider: ISyncBackend) {
        for (note in notes) {
            try {
                val mapping = idMappingDao.getByLocalIdAndProvider(note.id, CloudService.FILE_STORAGE)
                val existingFile = mapping?.let { syncProvider.getNote(it) }

                if (existingFile != null && mapping != null) {
                    idMappingDao.update(
                        mapping.copy(
                            storageUri = existingFile.idStr,
                            relativePath = existingFile.relativePath,
                            contentHash = existingFile.contentHash,
                            lastSyncedModified = existingFile.lastModified,
                        )
                    )
                } else {
                    if (mapping != null) {
                        idMappingDao.deleteByLocalIdAndProvider(note.id, CloudService.FILE_STORAGE)
                    }
                    val created = syncProvider.createNote(note)
                    idMappingDao.insert(created.getMapping(note.id, CloudService.FILE_STORAGE))
                    noteDao.update(
                        note.copy(
                            title = created.title,
                            modifiedDate = created.lastModified.takeIf { it > 0 } ?: note.modifiedDate,
                        ).toEntity()
                    )
                }
            } catch (e: Exception) {
                Log.e(tag, "restoreFileStorageNotes: Failed to restore note ${note.id}", e)
            }
        }
    }

    override suspend fun deleteNotes(vararg notes: Note, sync: Boolean) {
        Log.d(tag, "deleteNotes: Permanently deleting ${notes.size} notes")
        val array = notes.map { it.toEntity() }.toTypedArray()
        noteDao.delete(*array)
        if (sync) notes.filterNot { it.isLocalOnly }.forEach {
            processRemoteActions(it.id, Delete(it))
        }
    }

    override suspend fun discardEmptyNotes(): Boolean {
        val notes = noteDao.getAllBlankTitleNotes().first().filter { it.isEmpty() }.toTypedArray()
        Log.d(tag, "discardEmptyNotes: Found ${notes.size} empty notes to discard")
        deleteNotes(*notes)
        return notes.isNotEmpty()
    }

    override suspend fun permanentlyDeleteNotesInBin() {
        val deletedNotes = noteDao.getDeleted(defaultOf()).first()
        val noteIds = deletedNotes.map { it.id }.toLongArray()
        Log.d(tag, "permanentlyDeleteNotesInBin: Permanently deleting ${noteIds.size} notes from bin")

        if (backendProvider.syncProvider.value?.type == CloudService.FILE_STORAGE) {
            // Let the file delete finish before dropping the mappings, otherwise a leftover
            // file would be imported as a brand-new note on the next sync.
            deletedNotes.forEach { processRemoteActions(it.id, Delete(it)) }
        } else {
            idMappingDao.deleteByLocalId(*noteIds)
        }
        noteDao.permanentlyDeleteNotesInBin()
    }

    override fun getById(noteId: Long): Flow<Note?> {
        return noteDao.getById(noteId)
    }

    override fun getDeleted(sortMethod: SortMethod): Flow<List<Note>> {
        return noteDao.getDeleted(sortMethod)
    }

    override fun getArchived(sortMethod: SortMethod): Flow<List<Note>> {
        return noteDao.getArchived(sortMethod)
    }

    override fun getNonDeleted(sortMethod: SortMethod): Flow<List<Note>> {
        return noteDao.getNonDeleted(sortMethod)
    }

    override fun getNonDeletedOrArchived(sortMethod: SortMethod): Flow<List<Note>> {
        return noteDao.getNonDeletedOrArchived(sortMethod)
    }

    override fun getAll(sortMethod: SortMethod): Flow<List<Note>> {
        return noteDao.getAll(sortMethod)
    }

    override fun getByNotebook(notebookId: Long, sortMethod: SortMethod): Flow<List<Note>> {
        return noteDao.getByNotebook(notebookId, sortMethod)
    }

    override fun getNonRemoteNotes(provider: CloudService, sortMethod: SortMethod): Flow<List<Note>> {
        return noteDao.getNonRemoteNotes(sortMethod, provider)
    }

    override fun getNotesWithoutNotebook(sortMethod: SortMethod): Flow<List<Note>> {
        return noteDao.getNotesWithoutNotebook(sortMethod)
    }

    override suspend fun getNotesByCloudService(provider: CloudService): Map<IdMapping, Note?> {
        val allNotes = getAll().first().associateBy { it.id }
        val mappings = idMappingDao.getAllByCloudService(provider)
        Log.d(tag, "getNotesByCloudService: Found ${mappings.size} mappings for $provider")
        return mappings.associateWith { allNotes[it.localNoteId] }
    }

    override suspend fun deleteIdMappingsForCloudService(cloudService: CloudService) =
        idMappingDao.deleteAllMappingsFor(cloudService)
}
