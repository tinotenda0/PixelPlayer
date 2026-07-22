package com.theveloper.pixelplay.presentation.navigation

import com.theveloper.pixelplay.data.model.Song
import java.net.URLEncoder

/**
 * Artist navigation that works for streamed tracks as well as local ones.
 *
 * A gateway song has no row in the local artist table, so its [Song.artistId] is a placeholder.
 * Navigating with it produced "artist not found". The gateway accepts a name-derived id
 * (`yt-artistn-<url-encoded name>`) and resolves it upstream, so that's the fallback.
 */
object ArtistNavigation {

    /** Gateway id prefix for an artist identified by name rather than browse id. */
    private const val GATEWAY_NAME_PREFIX = "yt-artistn-"

    /**
     * Credited artists for a song, split out of the display string.
     *
     * The gateway joins collaborators with ", " (e.g. "Forrest Frank, Cory Asbury"). A single act
     * whose own name contains a comma ("Tyler, The Creator") arrives as one gateway entry, but we
     * can't tell the two apart from the joined string alone — so only split when it yields parts
     * that each look like a standalone name.
     */
    fun creditedArtists(song: Song): List<String> {
        val display = song.artist?.trim().orEmpty()
        if (display.isEmpty()) return emptyList()
        val parts = display.split(", ").map { it.trim() }.filter { it.isNotEmpty() }
        // "Tyler, The Creator" splits into ["Tyler", "The Creator"] — the tail is an article-led
        // fragment, not an artist. Treat that shape as a single name.
        if (parts.size == 2 && parts[1].startsWith("The ", ignoreCase = true)) {
            return listOf(display)
        }
        return parts.ifEmpty { listOf(display) }
    }

    /** Route to a specific artist by name, via the gateway's name lookup. */
    fun routeForName(name: String): String =
        Screen.ArtistDetail.createRoute(
            GATEWAY_NAME_PREFIX + URLEncoder.encode(name.trim(), "UTF-8")
        )

    /**
     * Best route to the song's (primary) artist: the local artist row when the song really is
     * local, otherwise a gateway name lookup.
     */
    fun routeFor(song: Song): String {
        val isGatewaySong = !song.navidromeId.isNullOrBlank() || song.id.startsWith("navidrome_")
        if (!isGatewaySong && song.artistId > 0L) {
            return Screen.ArtistDetail.createRoute(song.artistId)
        }
        val primary = creditedArtists(song).firstOrNull()
        return if (primary.isNullOrBlank()) {
            Screen.ArtistDetail.createRoute(song.artistId)
        } else {
            routeForName(primary)
        }
    }
}
