package org.qosp.notes.data.sync.fs

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.core.SyncNote
import org.qosp.notes.data.sync.getMapping
import org.qosp.notes.preferences.CloudService

class FileStorageReconcilerTest {

    private lateinit var reconciler: FileStorageReconciler

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        reconciler = FileStorageReconciler()
    }

    @Test
    fun `new file is imported`() {
        val result = reconciler(
            localNotes = emptyList(),
            files = listOf(file("inbox/hello.md", "Hello")),
            mappings = emptyList(),
        )

        assertEquals(1, result.imports.size)
        assertEquals("hello", result.imports.single().remoteNote.title)
        assertEquals("Hello", result.imports.single().note.content)
        assertTrue(result.removedLocalNotes.isEmpty())
    }

    @Test
    fun `moved file is followed via content hash`() {
        val f = file("inbox/moved.md", "Same content")
        val mapping = oldMapping(noteId = 1L, uri = "content://old/notes.md", path = "notes.md", hash = f.contentHash!!)

        val result = reconciler(
            localNotes = listOf(note(1L, "moved", "Same content")),
            files = listOf(f),
            mappings = listOf(mapping),
        )

        assertTrue(result.imports.isEmpty())
        assertTrue(result.removedLocalNotes.isEmpty())
        assertTrue(result.localUpdates.isEmpty())
        assertEquals("inbox/moved.md", result.updatedMappings.single().relativePath)
        assertEquals(f.idStr, result.updatedMappings.single().storageUri)
    }

    @Test
    fun `external rename updates the local title`() {
        val f = file("inbox/renamed.md", "Same content")
        val mapping = oldMapping(noteId = 1L, uri = "content://old/old-name.md", path = "old-name.md", hash = f.contentHash!!)

        val result = reconciler(
            localNotes = listOf(note(1L, "old-name", "Same content")),
            files = listOf(f),
            mappings = listOf(mapping),
        )

        assertEquals(1, result.localUpdates.size)
        assertEquals("renamed", result.localUpdates.single().remoteNote.title)
        assertEquals("inbox/renamed.md", result.updatedMappings.single().relativePath)
    }

    @Test
    fun `external delete removes the local note permanently`() {
        val local = note(1L, "gone", "Content")
        val mapping = oldMapping(noteId = 1L, uri = "content://old/gone.md", path = "gone.md", hash = ContentHash.of("Content"))

        val result = reconciler(
            localNotes = listOf(local),
            files = emptyList(),
            mappings = listOf(mapping),
        )

        assertEquals(listOf(local), result.removedLocalNotes)
        assertEquals(listOf(1L), result.removedMappingNoteIds)
        assertTrue(result.keptLocalNotes.isEmpty())
        assertTrue(result.remoteUpdates.isEmpty())
    }

    @Test
    fun `legacy mapping without a hash keeps the note instead of deleting blindly`() {
        val local = note(1L, "gone", "Content")
        val mapping = oldMapping(noteId = 1L, uri = "content://old/gone.md", path = "gone.md", hash = null)

        val result = reconciler(
            localNotes = listOf(local),
            files = emptyList(),
            mappings = listOf(mapping),
        )

        assertEquals(listOf(local), result.keptLocalNotes)
        assertTrue(result.removedLocalNotes.isEmpty())
        assertEquals(listOf(1L), result.removedMappingNoteIds)
    }

    @Test
    fun `external delete keeps a note with unsynced local edits`() {
        val local = note(1L, "edited", "New local content", modified = 200)
        val mapping = oldMapping(noteId = 1L, uri = "content://old/edited.md", path = "edited.md", hash = ContentHash.of("Old content"))

        val result = reconciler(
            localNotes = listOf(local),
            files = emptyList(),
            mappings = listOf(mapping),
        )

        assertEquals(listOf(local), result.keptLocalNotes)
        assertTrue(result.removedLocalNotes.isEmpty())
        assertEquals(listOf(1L), result.removedMappingNoteIds)
    }

    @Test
    fun `external file edit wins over local content`() {
        val f = file("note.md", "File content", modified = 200)
        val mapping = oldMapping(
            noteId = 1L,
            uri = f.idStr,
            path = "note.md",
            hash = ContentHash.of("Old content"),
        )

        val result = reconciler(
            localNotes = listOf(note(1L, "note", "Old content", modified = 100)),
            files = listOf(f),
            mappings = listOf(mapping),
        )

        assertEquals(1, result.localUpdates.size)
        assertEquals("File content", result.localUpdates.single().remoteNote.content)
        assertEquals(ContentHash.of("File content"), result.updatedMappings.single().contentHash)
        assertTrue(result.remoteUpdates.isEmpty())
    }

    @Test
    fun `local edit is written to the file`() {
        val f = file("note.md", "Old content", modified = 100)
        val mapping = oldMapping(
            noteId = 1L,
            uri = f.idStr,
            path = "note.md",
            hash = ContentHash.of("Old content"),
        )

        val result = reconciler(
            localNotes = listOf(note(1L, "note", "New local content", modified = 200)),
            files = listOf(f),
            mappings = listOf(mapping),
        )

        assertEquals(1, result.remoteUpdates.size)
        assertEquals("New local content", result.remoteUpdates.single().note.content)
        assertTrue(result.localUpdates.isEmpty())
    }

    @Test
    fun `when both sides changed the file wins`() {
        val f = file("note.md", "File content", modified = 200)
        val mapping = oldMapping(
            noteId = 1L,
            uri = f.idStr,
            path = "note.md",
            hash = ContentHash.of("Old content"),
        )

        val result = reconciler(
            localNotes = listOf(note(1L, "note", "Local content", modified = 200)),
            files = listOf(f),
            mappings = listOf(mapping),
        )

        assertEquals(1, result.localUpdates.size)
        assertEquals("File content", result.localUpdates.single().remoteNote.content)
        assertEquals(ContentHash.of("File content"), result.updatedMappings.single().contentHash)
        assertTrue(result.remoteUpdates.isEmpty())
    }

    @Test
    fun `local notes without a file are never uploaded`() {
        val result = reconciler(
            localNotes = listOf(note(1L, "local only", "Content")),
            files = emptyList(),
            mappings = emptyList(),
        )

        assertTrue(result.imports.isEmpty())
        assertTrue(result.remoteUpdates.isEmpty())
        assertTrue(result.removedLocalNotes.isEmpty())
    }

    @Test
    fun `adoption links a local note to a file by title`() {
        val f = file("note.md", "Content")
        val local = note(1L, "note", "Content")

        val result = reconciler(
            localNotes = listOf(local),
            files = listOf(f),
            mappings = emptyList(),
        )

        assertEquals(1, result.newMappings.size)
        assertEquals(1L, result.newMappings.single().localNoteId)
        assertTrue(result.imports.isEmpty())
        assertEquals("note.md", result.newMappings.single().relativePath)
    }

    @Test
    fun `adoption links a renamed local note by content hash`() {
        val f = file("new-name.md", "Same content")
        val local = note(1L, "old-name", "Same content")

        val result = reconciler(
            localNotes = listOf(local),
            files = listOf(f),
            mappings = emptyList(),
        )

        assertEquals(1, result.newMappings.size)
        assertEquals(1L, result.newMappings.single().localNoteId)
        assertEquals("new-name.md", result.newMappings.single().relativePath)
        assertTrue(result.imports.isEmpty())
    }

    @Test
    fun `unrelated file is imported`() {
        val result = reconciler(
            localNotes = listOf(note(1L, "local", "Local content")),
            files = listOf(file("foreign.md", "Foreign content")),
            mappings = emptyList(),
        )

        assertEquals(1, result.imports.size)
        assertTrue(result.newMappings.isEmpty())
    }

    @Test
    fun `ambiguous hash match does not delete the local note`() {
        val a = file("a.md", "Same")
        val b = file("b.md", "Same")
        val local = note(1L, "unknown", "Same")
        val mapping = oldMapping(
            noteId = 1L,
            uri = "content://old/unknown.md",
            path = "unknown.md",
            hash = ContentHash.of("Same"),
        )

        val result = reconciler(
            localNotes = listOf(local),
            files = listOf(a, b),
            mappings = listOf(mapping),
        )

        assertTrue(result.removedLocalNotes.isEmpty())
        assertTrue(result.keptLocalNotes.isEmpty())
        assertTrue(result.imports.isEmpty())
        assertTrue(result.updatedMappings.isEmpty())
    }

    @Test
    fun `a reused old path does not steal a moved note`() {
        val oldPathFile = file("note.md", "Different note")
        val movedFile = file("moved.md", "Original content")
        val mapping = oldMapping(
            noteId = 1L,
            uri = "content://old/note.md",
            path = "note.md",
            hash = ContentHash.of("Original content"),
        )

        val result = reconciler(
            localNotes = listOf(note(1L, "note", "Original content")),
            files = listOf(oldPathFile, movedFile),
            mappings = listOf(mapping),
        )

        assertEquals("moved.md", result.updatedMappings.single().relativePath)
        assertEquals(1, result.imports.size)
        assertEquals("Different note", result.imports.single().remoteNote.content)
        assertTrue(result.removedLocalNotes.isEmpty())
    }

    @Test
    fun `adoption prefers content hash over a matching filename`() {
        val f = file("same.md", "File content")
        val sameTitle = note(1L, "same", "Local content A")
        val sameContent = note(2L, "other", "File content")

        val result = reconciler(
            localNotes = listOf(sameTitle, sameContent),
            files = listOf(f),
            mappings = emptyList(),
        )

        assertEquals(1, result.newMappings.size)
        assertEquals(2L, result.newMappings.single().localNoteId)
        assertTrue(result.imports.isEmpty())
    }

    @Test
    fun `binned note whose file still exists queues a file delete`() {
        val f = file("note.md", "Content")
        val mapping = oldMapping(noteId = 1L, uri = f.idStr, path = "note.md", hash = f.contentHash!!)

        val result = reconciler(
            localNotes = emptyList(),
            files = listOf(f),
            mappings = listOf(mapping),
        )

        assertEquals(1, result.remoteDeletes.size)
        assertEquals(1L, result.remoteDeletes.single().note.id)
        assertTrue(result.imports.isEmpty())
    }

    @Test
    fun `txt files keep plain text mode`() {
        val f = file("note.txt", "Plain text")

        val result = reconciler(
            localNotes = emptyList(),
            files = listOf(f),
            mappings = emptyList(),
        )

        val imported = result.imports.single().note
        assertFalse(imported.isMarkdownEnabled)
    }

    @Test
    fun `line endings and trailing newlines do not count as edits`() {
        assertEquals(ContentHash.of("a\nb\n"), ContentHash.of("a\r\nb\r\n"))
        assertEquals(ContentHash.of("a\nb"), ContentHash.of("a\nb\n\n"))
    }

    private fun note(id: Long, title: String, content: String, modified: Long = 100L) = Note(
        id = id,
        title = title,
        content = content,
        modifiedDate = modified,
    )

    private fun file(path: String, content: String, modified: Long = 100L): SyncNote {
        val name = path.substringAfterLast('/')
        return SyncNote(
            id = 0L,
            idStr = "content://test/$path",
            content = content,
            title = name.substringBeforeLast('.'),
            lastModified = modified,
            relativePath = path,
            contentHash = ContentHash.of(content),
            fileExtension = name.substringAfterLast('.'),
            size = content.length.toLong(),
        )
    }

    private fun oldMapping(noteId: Long, uri: String, path: String, hash: String?): IdMapping {
        return SyncNote(
            id = 0L,
            idStr = uri,
            content = null,
            title = "",
            lastModified = 0L,
            relativePath = path,
            contentHash = hash,
        ).getMapping(noteId, CloudService.FILE_STORAGE)
    }
}
