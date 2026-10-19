package org.qosp.notes.data.sync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.qosp.notes.data.model.Note

class ProcessRemoteActionsMergeTest {

    private val first = Note(id = 1L, title = "First", content = "one")
    private val second = Note(id = 1L, title = "First", content = "two")

    @Test
    fun `create followed by update keeps a create with the latest content`() {
        val merged = mergeRemoteOperation(
            existing = RemoteOperation.Create(first),
            incoming = RemoteOperation.Update(second),
        )

        assertTrue(merged is RemoteOperation.Create)
        assertEquals("two", (merged as RemoteOperation.Create).note.content)
    }

    @Test
    fun `create followed by delete keeps the delete`() {
        val merged = mergeRemoteOperation(
            existing = RemoteOperation.Create(first),
            incoming = RemoteOperation.Delete(second),
        )

        assertTrue(merged is RemoteOperation.Delete)
    }

    @Test
    fun `update followed by update keeps the latest update`() {
        val merged = mergeRemoteOperation(
            existing = RemoteOperation.Update(first),
            incoming = RemoteOperation.Update(second),
        )

        assertEquals(second, (merged as RemoteOperation.Update).note)
    }

    @Test
    fun `an incoming create replaces everything`() {
        val merged = mergeRemoteOperation(
            existing = RemoteOperation.Update(first),
            incoming = RemoteOperation.Create(second),
        )

        assertEquals(second, (merged as RemoteOperation.Create).note)
    }
}
