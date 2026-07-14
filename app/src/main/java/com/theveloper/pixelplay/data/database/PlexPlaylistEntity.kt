package com.theveloper.pixelplay.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "plex_playlists")
data class PlexPlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "song_count") val songCount: Int,
    val duration: Long,
    @ColumnInfo(name = "last_sync_time") val lastSyncTime: Long
)
