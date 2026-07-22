package com.theveloper.pixelplay.data.search

import com.theveloper.pixelplay.data.model.Album
import com.theveloper.pixelplay.data.model.Artist
import com.theveloper.pixelplay.data.model.SearchResultItem
import com.theveloper.pixelplay.data.model.Song
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SearchRankerTest {

    private fun song(id: String, title: String, artist: String, album: String = "") =
        SearchResultItem.SongItem(
            Song.emptySong().copy(
                id = id,
                title = title,
                artist = artist,
                album = album,
                contentUriString = "test://$id",
                mimeType = "audio/mpeg",
                bitrate = 0,
                sampleRate = 0
            )
        )

    private fun artist(name: String) =
        SearchResultItem.ArtistItem(Artist.empty().copy(name = name, songCount = 5))

    private fun album(title: String, artist: String) =
        SearchResultItem.AlbumItem(Album.empty().copy(title = title, artist = artist))

    private val noPlays = emptyMap<String, SearchRanker.PlayStat>()

    @Test
    fun `artist ranks above songs that only match via the artist name`() {
        val items = listOf(
            song(id = "1", title = "Around the World", artist = "Daft Punk"),
            song(id = "2", title = "Harder Better Faster", artist = "Daft Punk"),
            artist(name = "Daft Punk"),
            album(title = "Discovery", artist = "Daft Punk")
        )
        val ranked = SearchRanker.rank("daft", items, noPlays)
        assertTrue(
            ranked.first() is SearchResultItem.ArtistItem,
            "Expected the Daft Punk artist on top, got ${ranked.first()}"
        )
    }

    @Test
    fun `exact song title still beats an unrelated weaker match`() {
        val items = listOf(
            song(id = "1", title = "Blinding Lights", artist = "The Weeknd"),
            artist(name = "Blinders")
        )
        val ranked = SearchRanker.rank("blinding lights", items, noPlays)
        assertTrue(ranked.first() is SearchResultItem.SongItem)
    }

    @Test
    fun `played songs rank above unplayed songs among equally-matching songs`() {
        // Both are equally strong (exact) title matches — play history breaks the tie.
        val items = listOf(
            song(id = "unplayed", title = "Save Your Tears", artist = "The Weeknd"),
            song(id = "played", title = "Save Your Tears", artist = "The Weeknd")
        )
        val stats = mapOf(
            "played" to SearchRanker.PlayStat(playCount = 40, lastPlayedTimestamp = 1_000L)
        )
        val ranked = SearchRanker.rank("save your tears", items, stats, nowMs = 2_000L)
        val first = ranked.first() as SearchResultItem.SongItem
        assertEquals("played", first.song.id, "The played song should rank first among equal matches")
    }

    @Test
    fun `spacing is ignored - lowkey matches Low Key`() {
        val items = listOf(
            song(id = "1", title = "Low Key", artist = "Some Artist"),
            song(id = "2", title = "Highway", artist = "Other")
        )
        val ranked = SearchRanker.rank("lowkey", items, noPlays)
        val first = ranked.first() as SearchResultItem.SongItem
        assertEquals("Low Key", first.song.title)
    }

    @Test
    fun `typo tolerance - daftt still finds Daft Punk`() {
        val items = listOf(
            artist(name = "Daft Punk"),
            artist(name = "Radiohead")
        )
        val ranked = SearchRanker.rank("daftt", items, noPlays)
        val first = ranked.first() as SearchResultItem.ArtistItem
        assertEquals("Daft Punk", first.artist.name)
    }

    @Test
    fun `order-free multi-word - around world finds Around the World`() {
        val items = listOf(
            song(id = "1", title = "Around the World", artist = "Daft Punk"),
            song(id = "2", title = "World Tour", artist = "Someone")
        )
        val ranked = SearchRanker.rank("around world", items, noPlays)
        val first = ranked.first() as SearchResultItem.SongItem
        assertEquals("Around the World", first.song.title)
    }

    @Test
    fun `blank query returns items unchanged`() {
        val items = listOf(artist("A"), artist("B"))
        assertEquals(items, SearchRanker.rank("   ", items, noPlays))
    }

    @Test
    fun `normalize strips case diacritics and punctuation`() {
        assertEquals("beyonce", SearchRanker.normalize("Beyoncé!"))
        assertEquals("lowkey", SearchRanker.normalize("Low-Key"))
        assertEquals("acdc", SearchRanker.normalize("AC/DC"))
    }

    @Test
    fun `upstream popularity order breaks ties that closeness alone would not`() {
        // Both titles match "love" as a word prefix and are the same length, so closeness is
        // identical. The gateway returned the popular one first; it must stay first.
        val items = listOf(
            song(id = "1", title = "Love Song", artist = "Popular Artist"),
            song(id = "2", title = "Love Bomb", artist = "Obscure Artist")
        )
        val ranked = SearchRanker.rank("love", items, noPlays)
        assertEquals("Love Song", (ranked.first() as SearchResultItem.SongItem).song.title)
    }

    @Test
    fun `popularity does not override a stronger textual match`() {
        // The exact match arrives second; match strength must still win over arrival order.
        val items = listOf(
            song(id = "1", title = "Loverboy", artist = "A"),
            song(id = "2", title = "Love", artist = "B")
        )
        val ranked = SearchRanker.rank("love", items, noPlays)
        assertEquals("Love", (ranked.first() as SearchResultItem.SongItem).song.title)
    }

    @Test
    fun `an artist the user actually listens to outranks an equally-matching stranger`() {
        val items = listOf(
            artist("Kendrick Lightyear"),                                  // no history
            artist("Kendrick Lamar"),                                      // heavy history
            song(id = "s1", title = "HUMBLE", artist = "Kendrick Lamar")
        )
        val plays = mapOf("s1" to SearchRanker.PlayStat(playCount = 40, lastPlayedTimestamp = 0L))
        val ranked = SearchRanker.rank("kendrick", items, plays)
        val firstArtist = ranked.filterIsInstance<SearchResultItem.ArtistItem>().first()
        assertEquals("Kendrick Lamar", firstArtist.artist.name)
    }

    @Test
    fun `upstream popularity beats merged position for equally-matching results`() {
        // Two artists matching identically, with equal-length names so closeness cancels out.
        // The popular one sits LATER in the merged list. Scoring off the merged index (what the
        // code did before per-source ranks) would put the unpopular one first purely for being
        // earlier in the concatenation.
        val unpopularFirst = artist("Kendrick Lamax")   // merged index 0, upstream rank 5
        val popularSecond = artist("Kendrick Lamar")    // merged index 1, upstream rank 0
        val merged = listOf(unpopularFirst, popularSecond)

        val ranks = mapOf(
            SearchRanker.itemKey(unpopularFirst) to 5,
            SearchRanker.itemKey(popularSecond) to 0
        )
        val ranked = SearchRanker.rank("kendrick", merged, noPlays, ranks)
        assertEquals(
            "Kendrick Lamar",
            (ranked.first() as SearchResultItem.ArtistItem).artist.name
        )
    }

    @Test
    fun `itemKey prefers the gateway id so local and live rows for one track agree`() {
        val local = song(id = "12345", title = "Last Last", artist = "Burna Boy")
        val live = song(id = "navidrome_yt-song-abc", title = "Last Last", artist = "Burna Boy")
        // Disjoint id spaces: keys only line up when a gateway id is present on both.
        assertEquals("s:12345", SearchRanker.itemKey(local))
        assertEquals("s:navidrome_yt-song-abc", SearchRanker.itemKey(live))
    }
}
