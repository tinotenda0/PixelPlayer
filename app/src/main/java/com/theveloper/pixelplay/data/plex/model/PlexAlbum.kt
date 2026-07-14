package com.theveloper.pixelplay.data.plex.model

import android.os.Parcelable
import androidx.compose.runtime.Immutable
import kotlinx.parcelize.Parcelize

@Immutable
@Parcelize
data class PlexAlbum(
    val id: String, // ratingKey
    val name: String,
    val artist: String,
    val artistId: String? = null,
    val songCount: Int = 0,
    val duration: Long = 0L,
    val year: Int = 0,
    val genre: String? = null
) : Parcelable {
    companion object {
        fun empty() = PlexAlbum(
            id = "",
            name = "",
            artist = "",
            artistId = null,
            songCount = 0,
            duration = 0L,
            year = 0,
            genre = null
        )
    }
}
