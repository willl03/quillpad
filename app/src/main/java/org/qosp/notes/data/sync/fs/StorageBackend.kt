package org.qosp.notes.data.sync.fs

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.core.AvailabilityStatus
import org.qosp.notes.data.sync.core.ISyncBackend
import org.qosp.notes.data.sync.core.SyncNote
import org.qosp.notes.data.sync.nextcloud.BackendValidationResult
import org.qosp.notes.preferences.CloudService
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import kotlin.time.measureTimedValue

class StorageBackend(private val context: Context, private val config: StorageConfig) : ISyncBackend {

    private data class ResolvedFile(val file: DocumentFile, val relativePath: String)
    private data class Candidate(val resolved: ResolvedFile, val syncNote: SyncNote?)

    private sealed interface Resolution {
        data class Found(val file: ResolvedFile) : Resolution
        data object NotFound : Resolution
        data object Ambiguous : Resolution
    }

    companion object {
        private const val TAG = "StorageBackend"

        /**
         * Serializes scans and writes. Sync and the debounced [org.qosp.notes.data.sync.core.ProcessRemoteActions]
         * writes can otherwise interleave while a file is being listed or replaced.
         */
        private val storageMutex = Mutex()
    }

    override val type: CloudService = CloudService.FILE_STORAGE

    override suspend fun isAvailable(): AvailabilityStatus {
        return try {
            val root = getRootDocumentFile()
            when {
                root == null -> AvailabilityStatus.Unavailable("Storage location not accessible")
                !hasPermissionsAt(config.location) -> AvailabilityStatus.Unavailable("Missing storage permissions - please reconfigure sync")
                !root.canRead() -> AvailabilityStatus.Unavailable("Cannot read from storage location")
                !root.canWrite() -> AvailabilityStatus.Unavailable("Cannot write to storage location")
                else -> AvailabilityStatus.Available
            }
        } catch (e: Exception) {
            AvailabilityStatus.Unavailable("Storage access error: ${e.message ?: "Unknown error"}")
        }
    }

    private val Note.filename: String
        get() {
            val titleToSet = title.ifBlank { "Untitled" }
            val ext = if (isMarkdownEnabled) "md" else "txt"
            return "${NoteFileNames.sanitize(titleToSet)}.$ext"
        }

    override suspend fun createNote(note: Note): SyncNote = storageMutex.withLock {
        val root = getRootDocumentFile() ?: throw IOException("Unable to access storage location")

        val mimeType = if (note.isMarkdownEnabled) "text/markdown" else "text/plain"
        val fileName = uniqueName(root, note.filename, currentName = null)
        val newDoc = root.createFile(mimeType, fileName)
            ?: throw IOException("Unable to create file $fileName")

        try {
            val content = note.toStorableContent()
            writeNoteToFile(newDoc, content)

            val resolved = resolveRelativePath(root, newDoc.name ?: fileName) ?: newDoc
            getFile(resolved, newDoc.name ?: fileName)
        } catch (e: Exception) {
            // Never leave an empty/partial file behind: the next scan would import it as a
            // brand-new note and the user would see a duplicate.
            runCatching { newDoc.delete() }
                .onFailure { Log.w(TAG, "createNote: Unable to clean up ${newDoc.name}", it) }
            throw e
        }
    }

    override suspend fun updateNote(note: Note, mapping: IdMapping): IdMapping = storageMutex.withLock {
        val root = getRootDocumentFile() ?: throw IOException("Unable to access storage location")
        val resolved = when (val resolution = resolveMapping(root, mapping)) {
            is Resolution.Found -> resolution.file
            Resolution.Ambiguous -> throw AmbiguousFileException(
                "Unable to identify the note file for ${mapping.relativePath ?: mapping.storageUri}"
            )
            Resolution.NotFound -> throw FileNotFoundException(
                "Note file no longer exists: ${mapping.relativePath ?: mapping.storageUri}"
            )
        }

        val content = note.toStorableContent()
        writeNoteToFile(resolved.file, content)

        var file = resolved.file
        var path = resolved.relativePath

        val requestedName = note.filename
        if (requestedName != file.name) {
            renameFile(root, file, path, requestedName)?.let { renamed ->
                file = renamed.file
                path = renamed.relativePath
            }
        }

        // Providers can report a stale timestamp immediately after a write; fall back to now.
        val modified = file.lastModified().takeIf { it > 0 }?.div(1000) ?: Instant.now().epochSecond
        mapping.copy(
            storageUri = file.uri.toString(),
            relativePath = path,
            contentHash = ContentHash.of(content),
            lastSyncedModified = modified,
        )
    }

    override suspend fun deleteNote(mapping: IdMapping): Boolean = storageMutex.withLock {
        val root = getRootDocumentFile() ?: return@withLock false
        val resolved = when (val resolution = resolveMapping(root, mapping)) {
            is Resolution.Found -> resolution.file
            // Never delete a file we cannot identify with certainty; the caller keeps the
            // mapping and the next sync can retry.
            Resolution.Ambiguous -> return@withLock false
            Resolution.NotFound -> return@withLock true
        }

        val result = inStorage {
            DocumentsContract.deleteDocument(context.contentResolver, resolved.file.uri)
        } ?: false

        Log.d(TAG, "deleteNote: Deleted (${result}) the file: ${resolved.relativePath}")
        result
    }

    override suspend fun getNote(mapping: IdMapping): SyncNote? = storageMutex.withLock {
        try {
            val root = getRootDocumentFile() ?: return@withLock null
            val resolved = when (val resolution = resolveMapping(root, mapping)) {
                is Resolution.Found -> resolution.file
                else -> return@withLock null
            }
            getFile(resolved.file, resolved.relativePath)
        } catch (e: Exception) {
            Log.e(TAG, "getNote: Error getting note with id ${mapping.localNoteId}", e)
            null
        }
    }

    override suspend fun getAll(): List<SyncNote>? = storageMutex.withLock {
        val root = getRootDocumentFile() ?: return@withLock null
        try {
            listNotes(root)
        } catch (e: Exception) {
            Log.e(TAG, "getAll: Error listing files", e)
            null
        }
    }

    suspend fun validateConfig(): BackendValidationResult {
        return try {
            val root = getRootDocumentFile()
            if (root == null || !hasPermissionsAt(config.location)) {
                BackendValidationResult.InvalidConfig
            } else {
                BackendValidationResult.Success
            }
        } catch (e: Exception) {
            Log.e(TAG, "validateConfig: Error validating config", e)
            BackendValidationResult.InvalidConfig
        }
    }

    private fun getRootDocumentFile(): DocumentFile? {
        return DocumentFile.fromTreeUri(context, config.location)
    }

    private fun hasPermissionsAt(uri: Uri): Boolean {
        val perm = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        return perm?.let { it.isReadPermission && it.isWritePermission } ?: false
    }

    /** Recursively lists every note file under [root]. Must be called with [storageMutex] held. */
    private fun listNotes(root: DocumentFile): List<SyncNote> {
        val files = mutableListOf<Pair<DocumentFile, String>>()
        walkNotes(root, "", files)
        return files.map { (file, path) -> getFile(file, path) }
    }

    private fun walkNotes(dir: DocumentFile, basePath: String, out: MutableList<Pair<DocumentFile, String>>) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            when {
                child.isDirectory -> {
                    if (NoteFileNames.isIgnoredDirectory(name)) continue
                    val childPath = if (basePath.isEmpty()) name else "$basePath/$name"
                    walkNotes(child, childPath, out)
                }

                child.isFile && NoteFileNames.isNoteFile(name) -> {
                    val childPath = if (basePath.isEmpty()) name else "$basePath/$name"
                    out += child to childPath
                }
            }
        }
    }

    private fun getFile(file: DocumentFile, relativePath: String): SyncNote {
        val name = file.name ?: relativePath.substringAfterLast('/')
        val content = readFileContent(file)
            ?: throw IOException("Unable to read note file: $relativePath")
        val modified = file.lastModified().takeIf { it > 0 }?.div(1000) ?: 0L

        return SyncNote(
            id = 0,
            idStr = file.uri.toString(),
            content = content,
            title = NoteFileNames.titleFromName(name),
            lastModified = modified,
            relativePath = relativePath,
            contentHash = ContentHash.of(content),
            fileExtension = NoteFileNames.extension(name),
            size = file.length(),
        )
    }

    private suspend fun writeNoteToFile(file: DocumentFile, content: String) {
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { output ->
            // "wt" truncates on providers that honour it; the explicit truncate covers the
            // rest (SEEKABLE FileOutputStreams from SAF).
            runCatching { (output as? FileOutputStream)?.channel?.truncate(0) }
            val bytesWritten = content.encodeToByteArray().inputStream().copyTo(output)
            Log.d(TAG, "writeNote: Wrote $bytesWritten bytes to ${file.name}")
        } ?: throw IOException("Unable to open output stream for ${file.name}")
    }

    private fun readFileContent(file: DocumentFile): String? {
        Log.d(TAG, "readFileContent: ${file.name}")
        return context.contentResolver.openInputStream(file.uri)?.use {
            it.bufferedReader(Charsets.UTF_8).readText()
        }
    }

    /**
     * Finds the current document for [mapping].
     *
     * SAF URIs embed the path, so they change when a file is moved or renamed elsewhere. The
     * last-synced content is the strongest identity: prefer a unique file with that content
     * over the remembered path, because the old path may have been reused by a different
     * note. If the candidates disagree and we cannot identify the file, return Ambiguous so
     * callers do not overwrite or delete the wrong document.
     */
    private fun resolveMapping(root: DocumentFile, mapping: IdMapping): Resolution {
        val baseHash = mapping.contentHash
        val lastPath = mapping.relativePath
        val lastFileName = lastPath?.substringAfterLast('/') ?: fileNameFromUri(mapping.storageUri)

        val direct = mapping.storageUri?.toUri()?.let { uri ->
            DocumentFile.fromSingleUri(context, uri)
                ?.takeIf { it.exists() && it.isFile }
                ?.let { file -> readCandidate(file, lastPath ?: file.name.orEmpty()) }
        }

        val atLastPath = lastPath?.let { path ->
            resolveRelativePath(root, path)?.let { file ->
                readCandidate(file, normalizePath(path))
            }
        }

        // Legacy mappings have no merge base yet: trust the remembered location.
        if (baseHash == null) {
            direct?.let { return Resolution.Found(it.resolved) }
            atLastPath?.let { return Resolution.Found(it.resolved) }
            return findByFileName(root, lastFileName)
        }

        // The file still has the content it had at the last sync: this is the note.
        direct?.takeIf { it.syncNote?.contentHash == baseHash }?.let {
            return Resolution.Found(it.resolved)
        }
        atLastPath?.takeIf { it.syncNote?.contentHash == baseHash }?.let {
            return Resolution.Found(it.resolved)
        }

        // The content is no longer at the remembered location. Look for it elsewhere first;
        // this catches a move plus a different file taking over the old path.
        val allNotes = runCatching { listNotes(root) }.getOrNull()
        if (allNotes != null) {
            val matches = allNotes.filter { it.contentHash == baseHash }
            when {
                matches.size == 1 -> return foundNote(matches.first())
                matches.size > 1 -> return Resolution.Ambiguous
            }
        }

        // No moved file. A readable candidate at the old location is probably an edit in
        // place, but only trust it while the filename still matches the last known name.
        val localCandidate = atLastPath ?: direct
        if (localCandidate != null) {
            if (localCandidate.syncNote == null) return Resolution.Ambiguous
            val name = localCandidate.resolved.file.name
            if (lastFileName.isNullOrBlank() || name.equals(lastFileName, ignoreCase = true)) {
                return Resolution.Found(localCandidate.resolved)
            }
            return Resolution.Ambiguous
        }

        return findByFileName(root, lastFileName)
    }

    private fun readCandidate(file: DocumentFile, relativePath: String): Candidate {
        return Candidate(
            resolved = ResolvedFile(file, relativePath),
            syncNote = runCatching { getFile(file, relativePath) }.getOrNull(),
        )
    }

    private fun foundNote(note: SyncNote): Resolution {
        val file = DocumentFile.fromSingleUri(context, note.idStr.toUri())
            ?: return Resolution.Ambiguous
        if (!file.exists() || !file.isFile) return Resolution.Ambiguous
        return Resolution.Found(ResolvedFile(file, note.relativePath.orEmpty()))
    }

    private fun findByFileName(root: DocumentFile, fileName: String?): Resolution {
        if (fileName.isNullOrBlank()) return Resolution.NotFound
        val allNotes = runCatching { listNotes(root) }.getOrNull()
            ?: return Resolution.Ambiguous
        val matches = allNotes.filter {
            it.relativePath?.substringAfterLast('/')?.equals(fileName, ignoreCase = true) == true
        }
        return when (matches.size) {
            0 -> Resolution.NotFound
            1 -> foundNote(matches.first())
            else -> Resolution.Ambiguous
        }
    }

    private fun resolveRelativePath(root: DocumentFile, relativePath: String): DocumentFile? {
        val normalized = normalizePath(relativePath)
        if (normalized.isEmpty()) return null

        var current: DocumentFile = root
        for (segment in normalized.split('/')) {
            val children = current.listFiles()
            val child = children.firstOrNull { it.name == segment }
                ?: children.firstOrNull { it.name.equals(segment, ignoreCase = true) }
                ?: return null
            current = child
        }
        return current.takeIf { it.isFile }
    }

    private fun resolveDirectory(root: DocumentFile, relativePath: String): DocumentFile? {
        val normalized = normalizePath(relativePath)
        if (normalized.isEmpty()) return root

        var current: DocumentFile = root
        for (segment in normalized.split('/')) {
            val children = current.listFiles()
            current = children.firstOrNull { it.name == segment }
                ?: children.firstOrNull { it.name.equals(segment, ignoreCase = true) }
                ?: return null
        }
        return current.takeIf { it.isDirectory }
    }

    private suspend fun renameFile(
        root: DocumentFile,
        file: DocumentFile,
        currentPath: String,
        newName: String,
    ): ResolvedFile? {
        val parentPath = normalizePath(currentPath).substringBeforeLast('/', "")
        val parent = resolveDirectory(root, parentPath) ?: return null
        val targetName = uniqueName(parent, newName, currentName = file.name)

        Log.d(TAG, "renameFile: Renaming ${file.name} to $targetName")
        val renamed = runCatching {
            DocumentsContract.renameDocument(context.contentResolver, file.uri, targetName)
        }.getOrNull()

        val newPath = if (parentPath.isEmpty()) targetName else "$parentPath/$targetName"

        if (renamed == null) {
            // The provider may take a moment to expose the rename, or the target name may be
            // rejected. Re-check the parent before giving up.
            repeat(4) { attempt ->
                parent.listFiles().firstOrNull { it.name == targetName }?.let {
                    return ResolvedFile(it, newPath)
                }
                if (attempt < 3) delay(150)
            }
            Log.w(TAG, "renameFile: Unable to rename ${file.name} to $targetName")
            return null
        }

        // Prefer the tree-based URI from the parent listing; some providers return a bare
        // document URI from renameDocument that cannot be renamed again.
        repeat(4) { attempt ->
            parent.listFiles().firstOrNull { it.name == targetName }?.let {
                return ResolvedFile(it, newPath)
            }
            if (attempt < 3) delay(150)
        }

        val fallback = DocumentFile.fromSingleUri(context, renamed)
        return fallback?.takeIf { it.exists() }?.let { ResolvedFile(it, newPath) }
    }

    private fun uniqueName(parent: DocumentFile, requestedName: String, currentName: String?): String {
        val names = parent.listFiles()
            .filter { currentName == null || !it.name.equals(currentName, ignoreCase = true) }
            .mapNotNull { it.name?.lowercase() }
            .toMutableSet()

        if (requestedName.lowercase() !in names) return requestedName

        val base = requestedName.substringBeforeLast('.', requestedName)
        val extension = requestedName.substringAfterLast('.', "")
        val suffix = if (extension.isEmpty()) "" else ".$extension"

        var index = 2
        while (true) {
            val candidate = "$base $index$suffix"
            if (candidate.lowercase() !in names) return candidate
            index++
        }
    }

    private fun normalizePath(path: String): String {
        return path.replace('\\', '/').trim('/')
    }

    private fun fileNameFromUri(uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        val path = Uri.decode(uri)
        return path.substringAfterLast('/').substringAfterLast(':').takeIf { it.isNotBlank() }
    }

    private inline fun <reified T> inStorage(block: () -> T): T? {
        return try {
            val duration = measureTimedValue {
                block()
            }
            Log.i(TAG, "inStorage: That took ${duration.duration} to complete")
            duration.value
        } catch (e: Exception) {
            Log.e(TAG, "Exception while storing: ${e.message}", e)
            null
        }
    }
}

/** The file exists, but the app cannot prove which document the mapping belongs to. */
class AmbiguousFileException(message: String) : IOException(message)
