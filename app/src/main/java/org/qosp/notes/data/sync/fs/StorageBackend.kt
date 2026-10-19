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
            return "${sanitizeName(titleToSet)}.$ext"
        }

    override suspend fun createNote(note: Note): SyncNote = storageMutex.withLock {
        val root = getRootDocumentFile() ?: throw IOException("Unable to access storage location")

        val mimeType = if (note.isMarkdownEnabled) "text/markdown" else "text/plain"
        val fileName = uniqueName(root, note.filename, currentName = null)
        val newDoc = root.createFile(mimeType, fileName)
            ?: throw IOException("Unable to create file $fileName")

        val content = note.toStorableContent()
        writeNoteToFile(newDoc, content)

        val resolved = resolveRelativePath(root, newDoc.name ?: fileName) ?: newDoc
        getFile(resolved, newDoc.name ?: fileName)
    }

    override suspend fun updateNote(note: Note, mapping: IdMapping): IdMapping = storageMutex.withLock {
        val root = getRootDocumentFile() ?: throw IOException("Unable to access storage location")
        val resolved = resolveMapping(root, mapping)
            ?: throw FileNotFoundException("Note file no longer exists: ${mapping.relativePath ?: mapping.storageUri}")

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
        val resolved = resolveMapping(root, mapping) ?: return@withLock true

        val result = inStorage {
            DocumentsContract.deleteDocument(context.contentResolver, resolved.file.uri)
        } ?: false

        Log.d(TAG, "deleteNote: Deleted (${result}) the file: ${resolved.relativePath}")
        result
    }

    override suspend fun getNote(mapping: IdMapping): SyncNote? = storageMutex.withLock {
        try {
            val root = getRootDocumentFile() ?: return@withLock null
            val resolved = resolveMapping(root, mapping) ?: return@withLock null
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
                    if (isIgnoredDirectoryName(name)) continue
                    val childPath = if (basePath.isEmpty()) name else "$basePath/$name"
                    walkNotes(child, childPath, out)
                }

                child.isFile && isNoteFileName(name) -> {
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
            title = titleFromName(name),
            lastModified = modified,
            relativePath = relativePath,
            contentHash = ContentHash.of(content),
            fileExtension = name.substringAfterLast('.', "md"),
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
     * SAF document URIs embed the path, so they change when a file is moved or renamed by
     * another app. Try the last known URI, then the relative path, then the content hash and
     * finally the filename. Returns null when the file really is gone.
     */
    private fun resolveMapping(root: DocumentFile, mapping: IdMapping): ResolvedFile? {
        val uri = mapping.storageUri?.toUri()
        if (uri != null) {
            val direct = DocumentFile.fromSingleUri(context, uri)
            if (direct != null && direct.exists() && direct.isFile) {
                val path = mapping.relativePath ?: findPathByUri(root, uri) ?: direct.name.orEmpty()
                return ResolvedFile(direct, path)
            }
        }

        mapping.relativePath?.let { path ->
            resolveRelativePath(root, path)?.let { return ResolvedFile(it, normalizePath(path)) }
        }

        mapping.contentHash?.let { hash ->
            val matches = runCatching { listNotes(root) }.getOrElse { emptyList() }
                .filter { it.contentHash == hash }
            if (matches.size == 1) {
                val match = matches.first()
                val file = DocumentFile.fromSingleUri(context, match.idStr.toUri())
                if (file != null && file.exists()) {
                    return ResolvedFile(file, match.relativePath ?: "")
                }
            }
        }

        val fileName = fileNameFromUri(mapping.storageUri)
        if (!fileName.isNullOrEmpty()) {
            val matches = mutableListOf<Pair<DocumentFile, String>>()
            walkNotes(root, "", matches)
            val match = matches.filter { it.first.name.equals(fileName, ignoreCase = true) }
            if (match.size == 1) return ResolvedFile(match.first().first, match.first().second)
        }

        return null
    }

    private fun findPathByUri(root: DocumentFile, uri: Uri): String? {
        val matches = mutableListOf<Pair<DocumentFile, String>>()
        walkNotes(root, "", matches)
        return matches.firstOrNull { it.first.uri == uri }?.second
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

    private fun sanitizeName(name: String): String {
        val cleaned = name
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), " ")
            .trim()
            .trimEnd('.')
        return cleaned.ifBlank { "Untitled" }.take(150)
    }

    private fun titleFromName(name: String): String {
        return if (name.contains('.')) name.substringBeforeLast('.') else name
    }

    private fun isNoteFileName(name: String): Boolean {
        if (name.startsWith(".")) return false
        if (name.contains("sync-conflict", ignoreCase = true)) return false
        return name.endsWith(".md", ignoreCase = true) || name.endsWith(".txt", ignoreCase = true)
    }

    private fun isIgnoredDirectoryName(name: String): Boolean {
        return name.startsWith(".") || name.contains("sync-conflict", ignoreCase = true)
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
