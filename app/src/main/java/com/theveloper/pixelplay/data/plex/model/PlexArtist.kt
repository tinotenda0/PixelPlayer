package com.theveloper.pixelplay.data.plex.model

import android.os.Parcelable
import androidx.compose.runtime.Immutable
import kotlinx.parcelize.Parcelize

@Immutable
@Parcelize
data class PlexArtist(
    val id: String, // ratingKey
    val name: String,
    val albumCount: Int = 0
) : Parcelable {
    companion object {
        fun empty() = PlexArtist(
            id = "",
            name = "",
            albumCount = 0
        )
    }
}
