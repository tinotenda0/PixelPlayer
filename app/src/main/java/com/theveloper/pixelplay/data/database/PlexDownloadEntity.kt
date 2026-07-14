package com.theveloper.pixelplay.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A Plex track that has been fully downloaded ("pinned") for offline playback.
 * Only completed downloads are persisted; in-flight state lives in
 * [com.theveloper.pixelplay.data.plex.PlexDownloadManager].
 */
@Entity(tableName = "plex_downloads")
data class PlexDownloadEntity(
    @PrimaryKey @ColumnInfo(name = "plex_id") val plexId: String,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "mime_type") val mimeType: String?,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "downloaded_at") val downloadedAt: Long
)
