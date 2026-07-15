package com.theveloper.pixelplay.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.theveloper.pixelplay.data.plex.model.PlexSong
import com.theveloper.pixelplay.data.model.Song

@Entity(
    tableName = "plex_songs",
    indices = [
        Index(value = ["plex_id"]),
        Index(value = ["playlist_id"]),
        Index(value = ["playlist_id", "date_added"])
    ]
)
data class PlexSongEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "plex_id") val plexId: String,
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    val title: String,
    val artist: String,
    @ColumnInfo(name = "artist_id") val artistId: String?,
    val album: String,
    @ColumnInfo(name = "album_id") val albumId: String?,
    val duration: Long,
    @ColumnInfo(name = "track_number") val trackNumber: Int,
    @ColumnInfo(name = "disc_number") val discNumber: Int,
    val year: Int,
    val genre: String?,
    val bitRate: Int?,
    @ColumnInfo(name = "mime_type") val mimeType: String?,
    val path: String,
    @ColumnInfo(name = "thumb_path") val thumbPath: String?,
    @ColumnInfo(name = "date_added") val dateAdded: Long
)

fun PlexSongEntity.toSong(): Song {
    return Song(
        id = "plex_$id",
        title = title,
        artist = artist,
        artistId = -1L,
        album = album,
        albumId = -1L,
        path = path,
        contentUriString = "plex://$plexId",
        albumArtUriString = "plex_cover://$plexId",
        duration = duration,
        genre = genre,
        mimeType = mimeType,
        bitrate = bitRate?.let { it * 1000 },
        sampleRate = null,
        year = year,
        trackNumber = trackNumber,
        dateAdded = dateAdded,
        isFavorite = false,
        plexId = plexId
    )
}

fun PlexSong.toEntity(playlistId: String): PlexSongEntity {
    return PlexSongEntity(
        id = "${playlistId}_$id",
        plexId = id,
        playlistId = playlistId,
        title = title,
        artist = artist,
        artistId = artistId,
        album = album,
        albumId = albumId,
        duration = duration,
        trackNumber = trackNumber,
        discNumber = discNumber,
        year = year,
        genre = genre,
        bitRate = bitRate,
        mimeType = resolvedMimeType,
        path = path,
        thumbPath = thumb,
        dateAdded = System.currentTimeMillis()
    )
}
