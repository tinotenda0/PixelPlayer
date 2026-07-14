package com.theveloper.pixelplay.data.plex.model

import android.os.Parcelable
import androidx.compose.runtime.Immutable
import kotlinx.parcelize.Parcelize

@Immutable
@Parcelize
data class PlexSong(
    val id: String, // ratingKey
    val title: String,
    val artist: String,
    val artistId: String? = null, // grandparentRatingKey
    val album: String,
    val albumId: String? = null, // parentRatingKey
    val duration: Long, // milliseconds
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val year: Int = 0,
    val genre: String? = null,
    val bitRate: Int? = null, // kbps
    val contentType: String? = null,
    val path: String = "",
    val size: Long? = null,
    val playCount: Int = 0,
    /** Server art path (e.g. /library/metadata/{albumId}/thumb/123), inherited from album/artist when absent on the track. */
    val thumb: String? = null
) : Parcelable {
    companion object {
        fun empty() = PlexSong(
            id = "",
            title = "",
            artist = "",
            artistId = null,
            album = "",
            albumId = null,
            duration = 0L,
            trackNumber = 0,
            discNumber = 0,
            year = 0,
            genre = null,
            bitRate = null,
            contentType = null,
            path = "",
            size = null,
            playCount = 0
        )
    }

    val resolvedMimeType: String
        get() = when {
            contentType != null -> contentType
            else -> "audio/mpeg"
        }
}
