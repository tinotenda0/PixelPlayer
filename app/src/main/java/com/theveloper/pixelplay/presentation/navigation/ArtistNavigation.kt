package com.theveloper.pixelplay.presentation.navigation

import android.net.Uri
import com.theveloper.pixelplay.data.model.ArtistRef
import com.theveloper.pixelplay.data.model.Song

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
            // Uri.encode, NOT URLEncoder: URLEncoder is FORM encoding, which turns spaces into
            // "+". Nothing downstream converts "+" back, so "Forrest Frank" arrived at the
            // gateway as "Forrest+Frank" and never resolved.
            GATEWAY_NAME_PREFIX + Uri.encode(name.trim())
        )

    /** Route to a specific credited artist, using the gateway's own id when we have one. */
    fun routeForRef(ref: ArtistRef): String {
        val name = ref.name.takeIf { it.isNotBlank() }
        ref.gatewayId?.takeIf { it.isNotBlank() }?.let {
            return Screen.ArtistDetail.createRoute(it, name)
        }
        // Negative ids are synthesized for streamed/synced content and resolve to nothing.
        return if (ref.id > 0L) Screen.ArtistDetail.createRoute(ref.id, name)
               else routeForName(ref.name)
    }

    /**
     * Best route to the song's (primary) artist: the local artist row when the song really is
     * local, otherwise a gateway name lookup.
     */
    fun routeFor(song: Song): String {
        // The display name always travels with the route as a fallback, so even a wrong or
        // missing id can still resolve rather than dead-ending.
        val fallbackName = creditedArtists(song).firstOrNull()?.takeIf { it.isNotBlank() }
            ?: song.artist?.trim()?.takeIf { it.isNotEmpty() }

        // Structured identity first: exact, and the only thing that works for a song the
        // server has never cached.
        song.artists.firstOrNull { it.isPrimary }?.let { return routeForRef(it) }
        song.artists.firstOrNull()?.let { return routeForRef(it) }

        // Only a POSITIVE id is a real local artist row. -1 (streamed) and the negative
        // synthesized ids used for synced content both resolve to nothing, so they must never
        // be routed on their own.
        if (song.artistId > 0L) {
            return Screen.ArtistDetail.createRoute(song.artistId, fallbackName)
        }
        return if (fallbackName != null) routeForName(fallbackName)
               else Screen.ArtistDetail.createRoute(song.artistId)
    }
}
