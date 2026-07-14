package com.theveloper.pixelplay.data.plex.model

import android.os.Parcelable
import androidx.compose.runtime.Immutable
import kotlinx.parcelize.Parcelize

@Immutable
@Parcelize
data class PlexPlaylist(
    val id: String, // ratingKey
    val name: String,
    val songCount: Int = 0,
    val duration: Long = 0L,
    val created: Long = 0L,
    val changed: Long = 0L
) : Parcelable {
    companion object {
        fun empty() = PlexPlaylist(
            id = "",
            name = "",
            songCount = 0,
            duration = 0L,
            created = 0L,
            changed = 0L
        )
    }
}
