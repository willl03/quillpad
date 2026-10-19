package org.qosp.notes.data.model

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import org.qosp.notes.preferences.CloudService

@Serializable
@Parcelize
@Entity(
    tableName = "cloud_ids",
    indices = [Index(value = ["localNoteId"], name = "cloud_ids_id_index"),
        Index(value = ["localNoteId", "provider"], name = "cloud_ids_id_provider_index")],
)
data class IdMapping(
    @PrimaryKey(autoGenerate = true)
    val mappingId: Long = 0L,
    val localNoteId: Long,
    val remoteNoteId: Long?,
    val provider: CloudService?,
    val extras: String?,
    val isDeletedLocally: Boolean,
    val isBeingUpdated: Boolean = false,
    val storageUri: String? = null,
    /** Path relative to the storage root, e.g. "note.md" or "folder/note.md". */
    val relativePath: String? = null,
    /** Hash of the file content at the last successful sync. Used as the 3-way merge base. */
    val contentHash: String? = null,
    /** File timestamp at the last successful sync, in epoch seconds. */
    val lastSyncedModified: Long? = null,
) : Parcelable
