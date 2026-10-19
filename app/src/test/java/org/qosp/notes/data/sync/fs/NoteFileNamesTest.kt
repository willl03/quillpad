package org.qosp.notes.data.sync.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteFileNamesTest {

    @Test
    fun `leading dots are removed so the file is not hidden`() {
        assertEquals("secret", NoteFileNames.sanitize(".secret"))
        assertEquals("obsidian note", NoteFileNames.sanitize("..obsidian note"))
    }

    @Test
    fun `sync conflict markers are removed`() {
        assertEquals("sync conflict notes", NoteFileNames.sanitize("sync-conflict notes"))
        assertEquals("sync conflict", NoteFileNames.sanitize("Sync-Conflict"))
    }

    @Test
    fun `invalid characters become spaces`() {
        assertEquals("a b c", NoteFileNames.sanitize("a/b:c"))
        assertEquals("a b", NoteFileNames.sanitize("a?b"))
    }

    @Test
    fun `blank and dot-only titles fall back to Untitled`() {
        assertEquals(NoteFileNames.FALLBACK, NoteFileNames.sanitize(""))
        assertEquals(NoteFileNames.FALLBACK, NoteFileNames.sanitize("."))
        assertEquals(NoteFileNames.FALLBACK, NoteFileNames.sanitize("..."))
    }

    @Test
    fun `trailing dots are removed`() {
        assertEquals("foo", NoteFileNames.sanitize("foo."))
    }

    @Test
    fun `hidden and conflict files are not note files`() {
        assertFalse(NoteFileNames.isNoteFile(".hidden.md"))
        assertFalse(NoteFileNames.isNoteFile("foo.sync-conflict-123.md"))
        assertTrue(NoteFileNames.isNoteFile("foo.md"))
        assertTrue(NoteFileNames.isNoteFile("foo.TXT"))
    }

    @Test
    fun `title and extension are split at the last dot`() {
        assertEquals("foo.bar", NoteFileNames.titleFromName("foo.bar.md"))
        assertEquals("MD", NoteFileNames.extension("foo.MD"))
    }
}
