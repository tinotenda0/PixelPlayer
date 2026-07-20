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
}
