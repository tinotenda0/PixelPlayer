package com.theveloper.pixelplay.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A Subsonic/YouTube track that has been fully downloaded ("pinned") for offline playback.
 * Only completed downloads are persisted; in-flight state lives in
 * [com.theveloper.pixelplay.data.navidrome.NavidromeDownloadManager].
 *
 * The key is the Subsonic song id (a real server id, or a `yt-<videoId>` on-demand id).
 */
@Entity(tableName = "navidrome_downloads")
data class NavidromeDownloadEntity(
    @PrimaryKey @ColumnInfo(name = "navidrome_id") val navidromeId: String,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "mime_type") val mimeType: String?,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "downloaded_at") val downloadedAt: Long,
    // Track metadata captured at pin time so a pinned song is fully browsable/playable OFFLINE,
    // without needing the synced library (on-demand yt- tracks aren't in it). All nullable so the
    // v46->v47 migration can add them to existing rows with a NULL default.
    @ColumnInfo(name = "title") val title: String? = null,
    @ColumnInfo(name = "artist") val artist: String? = null,
    @ColumnInfo(name = "album") val album: String? = null,
    @ColumnInfo(name = "album_art_uri") val albumArtUri: String? = null,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    @ColumnInfo(name = "artist_refs") val artistRefs: String? = null,
)
