package com.theveloper.pixelplay.data.stats

import com.theveloper.pixelplay.data.model.Song

/**
 * Title/artist/album/cover captured when a listening session starts, carried through to the
 * listening event the gateway is told about.
 *
 * Without this, [PlaybackStatsRepository.recordPlayback] can only re-derive metadata by looking
 * the song id up in the local synced library, which has no row for anything reached through live
 * gateway browsing (search results, genre browse, radio/similar-songs, home rows) — those plays
 * were reported with blank metadata. The playing surface always knows these fields already, so it
 * passes them down instead of making the repository guess.
 */
data class TrackMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val cover: String? = null
) {
    val isEmpty: Boolean
        get() = title.isNullOrBlank() &&
            artist.isNullOrBlank() &&
            album.isNullOrBlank() &&
            cover.isNullOrBlank()

    companion object {
        val EMPTY = TrackMetadata()

        fun from(song: Song): TrackMetadata = TrackMetadata(
            title = song.title,
            artist = song.displayArtist,
            album = song.album,
            cover = song.albumArtUriString
        )
    }
}
