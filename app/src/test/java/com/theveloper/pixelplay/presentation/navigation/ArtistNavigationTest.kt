package com.theveloper.pixelplay.presentation.navigation

import com.theveloper.pixelplay.data.model.ArtistRef
import com.theveloper.pixelplay.data.model.Song
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the routes an artist tap produces.
 *
 * The recurring failure was routing on Song.artistId, which is -1 for any streamed song and a
 * negative synthesized value for synced content — both dead-end at "Could not find the artist".
 * These assert the shape of the route, which is the part that decides whether the lookup can
 * possibly succeed.
 */
class ArtistNavigationTest {

    private fun streamedSong(
        artist: String = "Sam Fender, Olivia Dean",
        artists: List<ArtistRef> = emptyList(),
    ) = Song.emptySong().copy(
        id = "navidrome_yt-abc123",
        title = "Rein Me In",
        artist = artist,
        artistId = -1L,
        artists = artists,
        navidromeId = "yt-abc123",
        contentUriString = "navidrome://yt-abc123",
        mimeType = "audio/mp4",
        bitrate = 0,
        sampleRate = 0,
    )

    /** The dead end: a route ending in a bare non-positive id can never resolve. */
    private fun assertResolvable(route: String) {
        assertFalse(
            route == "artist_detail/-1" || route.startsWith("artist_detail/-1?") ||
                Regex("""artist_detail/-\d+$""").matches(route),
            "route must never be a bare non-positive id, was: $route",
        )
        assertTrue(route.startsWith("artist_detail/"), "unexpected route: $route")
    }

    @Test
    fun `streamed song with gateway refs routes to the real artist id`() {
        val route = ArtistNavigation.routeFor(
            streamedSong(
                artists = listOf(
                    ArtistRef(id = -1L, name = "Sam Fender", isPrimary = true, gatewayId = "yt-artist-UCabc"),
                    ArtistRef(id = -1L, name = "Olivia Dean", gatewayId = "yt-artist-UCdef"),
                ),
            ),
        )
        assertResolvable(route)
        assertTrue(route.contains("yt-artist-UCabc"), "should use the primary gateway id: $route")
    }

    /** The case that produced "Could not find the artist": no refs at all. */
    @Test
    fun `streamed song with no refs falls back to a name lookup, never a dead id`() {
        val route = ArtistNavigation.routeFor(streamedSong(artist = "Coldplay"))
        assertResolvable(route)
        // Only the prefix is asserted: android.net.Uri is a stub on the JVM, so the encoded
        // name comes back as "null" here. The invariant that matters — that this is a NAME
        // lookup rather than a dead numeric id — still holds.
        assertTrue(route.contains("yt-artistn-"), "should fall back to the name lookup: $route")
    }

    @Test
    fun `a ref carrying only a local placeholder id still routes by name`() {
        val route = ArtistNavigation.routeForRef(
            ArtistRef(id = -1L, name = "Sam Fender", isPrimary = true, gatewayId = null),
        )
        assertResolvable(route)
        assertTrue(route.contains("yt-artistn-"), "should route by name: $route")
    }

    @Test
    fun `a genuinely local artist still routes by its row id`() {
        val local = Song.emptySong().copy(
            id = "42", title = "Local", artist = "Someone", artistId = 42L,
            contentUriString = "content://media/42", mimeType = "audio/mpeg",
            bitrate = 0, sampleRate = 0,
        )
        val route = ArtistNavigation.routeFor(local)
        assertTrue(route.startsWith("artist_detail/42"), "expected the local row id: $route")
    }
}
