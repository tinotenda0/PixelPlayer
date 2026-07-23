package com.theveloper.pixelplay.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.navidrome.model.NavidromeArtistRef
import com.theveloper.pixelplay.data.navidrome.model.NavidromeSong

/**
 * Represents a song cached from a Navidrome/Subsonic server.
 *
 * @property id The composite ID (playlistId_songId)
 * @property navidromeId The unique song ID from the Subsonic server
 * @property playlistId The ID of the playlist this song belongs to
 * @property title The song title
 * @property artist The artist name
 * @property artistId The artist ID on the server (optional)
 * @property album The album name
 * @property albumId The album ID on the server (optional)
 * @property coverArtId The cover art ID used to construct the cover art URL
 * @property duration The duration in milliseconds
 * @property trackNumber The track number
 * @property discNumber The disc number
 * @property year The release year
 * @property genre The genre
 * @property bitRate The bitrate in kbps
 * @property mimeType The MIME type
 * @property suffix The file suffix (mp3, flac, etc.)
 * @property path The file path on the server
 * @property dateAdded The timestamp when this record was added
 */
@Entity(
    tableName = "navidrome_songs",
    indices = [
        Index(value = ["navidrome_id"]),
        Index(value = ["playlist_id"]),
        Index(value = ["playlist_id", "date_added"])
    ]
)
data class NavidromeSongEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "navidrome_id") val navidromeId: String,
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    val title: String,
    val artist: String,
    @ColumnInfo(name = "artist_id") val artistId: String?,
    /**
     * The gateway's per-credit artist identities, as `id\u001Fname` pairs joined by `\u001E`.
     *
     * [artist] is display text ("A, B") and cannot be split back into artists reliably — a comma
     * is more often part of one act's name than a separator. Without these ids a collaboration
     * collapsed into a single fake artist in the library. Null for servers that don't send
     * OpenSubsonic `artists[]`.
     */
    @ColumnInfo(name = "artist_refs") val artistRefs: String? = null,
    val album: String,
    @ColumnInfo(name = "album_id") val albumId: String?,
    @ColumnInfo(name = "cover_art_id") val coverArtId: String?,
    val duration: Long,
    @ColumnInfo(name = "track_number") val trackNumber: Int,
    @ColumnInfo(name = "disc_number") val discNumber: Int,
    val year: Int,
    val genre: String?,
    val bitRate: Int?,
    @ColumnInfo(name = "mime_type") val mimeType: String?,
    val suffix: String?,
    val path: String,
    @ColumnInfo(name = "date_added") val dateAdded: Long
)

/**
 * Convert a [NavidromeSongEntity] to the app's [Song] data model.
 */
fun NavidromeSongEntity.toSong(): Song {
    return Song(
        id = "navidrome_$id",
        title = title,
        artist = artist,
        artistId = -1L,
        // Carry the gateway credits so a cached song's artists are as openable as a live one's.
        artists = decodeArtistRefs(artistRefs).mapIndexed { index, ref ->
            com.theveloper.pixelplay.data.model.ArtistRef(
                id = -1L, name = ref.name, isPrimary = index == 0, gatewayId = ref.id
            )
        },
        album = album,
        albumId = -1L,
        path = path,
        contentUriString = "navidrome://$navidromeId",
        albumArtUriString = coverArtId?.let { "navidrome_cover://$it" },
        duration = duration,
        genre = genre,
        mimeType = mimeType,
        bitrate = bitRate?.let { it * 1000 },
        sampleRate = null,
        year = year,
        trackNumber = trackNumber,
        dateAdded = dateAdded,
        isFavorite = false,
        navidromeId = navidromeId
    )
}

/**
 * Convert a [NavidromeSong] to a [NavidromeSongEntity] for database storage.
 */
fun NavidromeSong.toEntity(playlistId: String): NavidromeSongEntity {
    return NavidromeSongEntity(
        id = "${playlistId}_$id",
        navidromeId = id,
        playlistId = playlistId,
        title = title,
        artist = artist,
        artistId = artistId,
        artistRefs = encodeArtistRefs(artistRefs),
        album = album,
        albumId = albumId,
        coverArtId = coverArt,
        duration = duration,
        trackNumber = trackNumber,
        discNumber = discNumber,
        year = year,
        genre = genre,
        bitRate = bitRate,
        mimeType = resolvedMimeType,
        suffix = suffix,
        path = path,
        dateAdded = System.currentTimeMillis()
    )
}

private const val REF_FIELD_SEP = '\u001F'
private const val REF_ENTRY_SEP = '\u001E'

/** Serialize gateway artist credits for storage. Uses control chars no artist name contains. */
fun encodeArtistRefs(refs: List<NavidromeArtistRef>): String? =
    if (refs.isEmpty()) null
    else refs.joinToString(REF_ENTRY_SEP.toString()) { "${it.id}$REF_FIELD_SEP${it.name}" }

/** Inverse of [encodeArtistRefs]; tolerates malformed rows by skipping them. */
fun decodeArtistRefs(encoded: String?): List<NavidromeArtistRef> {
    if (encoded.isNullOrEmpty()) return emptyList()
    return encoded.split(REF_ENTRY_SEP).mapNotNull { entry ->
        val parts = entry.split(REF_FIELD_SEP)
        if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) null
        else NavidromeArtistRef(parts[0], parts[1])
    }
}
