package org.qosp.notes.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.core.SyncNote
import org.qosp.notes.data.sync.fs.ContentHash

class ConvertersTest {

    @Test
    fun `txt file turns an existing markdown note into plain text`() {
        val local = Note(title = "note", content = "old", isMarkdownEnabled = true)
        val file = syncNote(content = "new", extension = "txt")

        val updated = file.updateLocalNote(local)

        assertFalse(updated.isMarkdownEnabled)
        assertEquals("new", updated.content)
    }

    @Test
    fun `md file turns a plain text note into markdown`() {
        val local = Note(title = "note", content = "old", isMarkdownEnabled = false)
        val file = syncNote(content = "new", extension = "md")

        val updated = file.updateLocalNote(local)

        assertTrue(updated.isMarkdownEnabled)
    }

    @Test
    fun `unknown extension keeps the local editor mode`() {
        val local = Note(title = "note", content = "old", isMarkdownEnabled = false)
        val file = syncNote(content = "new", extension = null)

        val updated = file.updateLocalNote(local)

        assertFalse(updated.isMarkdownEnabled)
    }

    private fun syncNote(content: String, extension: String?) = SyncNote(
        id = 0L,
        idStr = "content://test/note",
        content = content,
        title = "note",
        lastModified = 100L,
        relativePath = "note.${extension ?: "md"}",
        contentHash = ContentHash.of(content),
        fileExtension = extension,
    )
}
