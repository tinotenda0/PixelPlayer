package com.theveloper.pixelplay.data.stats

import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.database.SongEntity
import com.theveloper.pixelplay.data.model.ArtistRef
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.navidrome.NavidromeRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.jupiter.api.Test

class PlaybackStatsRepositoryTest {

    @Test
    fun `loadSummary excludes event that only touches the start boundary`() = runTest {
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val now = LocalDate.of(2026, 4, 10)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()

        val boundaryTouchingEvent = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = now,
            durationMs = 10_000L,
            startTimestamp = now - 10_000L,
            endTimestamp = now
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(song("song-1")),
            allEvents = listOf(boundaryTouchingEvent),
            nowMillis = now
        )

        assertThat(summary.totalDurationMs).isEqualTo(0L)
        assertThat(summary.totalPlayCount).isEqualTo(0)
    }

    @Test
    fun `loadSummary preserves playback longer than track duration`() = runTest {
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val start = LocalDate.of(2026, 4, 10)
            .atTime(10, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val listenedMs = TimeUnit.MINUTES.toMillis(15)
        val event = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = start + listenedMs,
            durationMs = listenedMs,
            startTimestamp = start,
            endTimestamp = start + listenedMs
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(song("song-1", durationMs = TimeUnit.MINUTES.toMillis(3))),
            allEvents = listOf(event),
            nowMillis = start + listenedMs + 1_000L
        )

        assertThat(summary.totalDurationMs).isEqualTo(listenedMs)
        assertThat(summary.totalPlayCount).isEqualTo(1)
        assertThat(summary.songs.single().totalDurationMs).isEqualTo(listenedMs)
    }

    @Test
    fun `loadSummary does not count short gaps between spans as listened time`() = runTest {
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val start = LocalDate.of(2026, 4, 10)
            .atTime(12, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val firstDurationMs = 10_000L
        val secondDurationMs = 10_000L
        val gapMs = 1_000L
        val events = listOf(
            PlaybackStatsRepository.PlaybackEvent(
                songId = "song-1",
                timestamp = start + firstDurationMs,
                durationMs = firstDurationMs,
                startTimestamp = start,
                endTimestamp = start + firstDurationMs
            ),
            PlaybackStatsRepository.PlaybackEvent(
                songId = "song-2",
                timestamp = start + firstDurationMs + gapMs + secondDurationMs,
                durationMs = secondDurationMs,
                startTimestamp = start + firstDurationMs + gapMs,
                endTimestamp = start + firstDurationMs + gapMs + secondDurationMs
            )
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(song("song-1"), song("song-2")),
            allEvents = events,
            nowMillis = start + firstDurationMs + gapMs + secondDurationMs + 1_000L
        )

        assertThat(summary.totalDurationMs).isEqualTo(firstDurationMs + secondDurationMs)
        assertThat(summary.totalPlayCount).isEqualTo(2)
    }

    @Test
    fun `buildSummaryFromEvents uses event spans without filesystem persistence`() = runTest {
        val repository = createRepository()
        val start = LocalDate.of(2026, 4, 10)
            .atTime(9, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val durationMs = 30_000L
        val events = listOf(
            PlaybackStatsRepository.PlaybackEvent(
                songId = "song-1",
                timestamp = start + durationMs,
                durationMs = durationMs,
                startTimestamp = start,
                endTimestamp = start + durationMs
            )
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(song("song-1")),
            allEvents = events,
            nowMillis = start + durationMs + 1_000L
        )

        assertThat(summary.totalDurationMs).isEqualTo(durationMs)
        assertThat(summary.uniqueSongs).isEqualTo(1)
    }

    @Test
    fun `loadSummary separates multi artist playback in top artists`() = runTest {
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val start = LocalDate.of(2026, 4, 10)
            .atTime(14, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val durationMs = 60_000L
        val event = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = start + durationMs,
            durationMs = durationMs,
            startTimestamp = start,
            endTimestamp = start + durationMs
        )
        val collaboration = song(
            songId = "song-1",
            artist = "Artist A",
            artists = listOf(
                ArtistRef(id = 1L, name = "Artist A", isPrimary = true),
                ArtistRef(id = 2L, name = "Artist B", isPrimary = false)
            )
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(collaboration),
            allEvents = listOf(event),
            nowMillis = start + durationMs + 1_000L
        )

        assertThat(summary.topArtists.map { it.artist })
            .containsExactly("Artist A", "Artist B")
            .inOrder()
        summary.topArtists.forEach { artist ->
            assertThat(artist.totalDurationMs).isEqualTo(durationMs)
            assertThat(artist.playCount).isEqualTo(1)
            assertThat(artist.uniqueSongs).isEqualTo(1)
        }
    }

    @Test
    fun `loadSummary counts separated artists in genre uniqueness`() = runTest {
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val start = LocalDate.of(2026, 4, 10)
            .atTime(15, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val durationMs = 30_000L
        val event = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = start + durationMs,
            durationMs = durationMs,
            startTimestamp = start,
            endTimestamp = start + durationMs
        )
        val collaboration = song(
            songId = "song-1",
            artist = "Artist A",
            artists = listOf(
                ArtistRef(id = 1L, name = "Artist A", isPrimary = true),
                ArtistRef(id = 2L, name = "Artist B", isPrimary = false)
            ),
            genre = "Pop"
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(collaboration),
            allEvents = listOf(event),
            nowMillis = start + durationMs + 1_000L
        )

        assertThat(summary.topGenres.single().uniqueArtists).isEqualTo(2)
    }

    @Test
    fun `recordPlayback reports live browsed track with the metadata it was given`() = runTest {
        // A live-browse id ("navidrome_<rawId>") has no MusicDao row at all, so without the
        // metadata the caller hands down, this event goes out with blank title/artist/album.
        val musicDao = mockk<MusicDao>(relaxed = true)
        val outbox = mockk<ListeningEventOutbox>(relaxed = true)
        val navidromeRepository = mockk<NavidromeRepository>(relaxed = true)
        val repository = createRepository(
            musicDao = musicDao,
            navidromeRepository = navidromeRepository,
            outbox = outbox
        )
        stubGatewayUpload(navidromeRepository)
        val enqueued = stubOutboxEnqueue(outbox)

        repository.recordPlayback(
            songId = "navidrome_yt-live-browse",
            durationMs = TimeUnit.SECONDS.toMillis(42),
            timestamp = 1_700_000_000_000L,
            metadata = TrackMetadata(
                title = "Live Browsed Track",
                artist = "Browsed Artist",
                album = "Browsed Album",
                cover = "https://gateway.example/cover/yt-live-browse"
            )
        )

        val event = checkNotNull(enqueued.value)
        assertThat(event.navidromeId).isEqualTo("yt-live-browse")
        assertThat(event.title).isEqualTo("Live Browsed Track")
        assertThat(event.artist).isEqualTo("Browsed Artist")
        assertThat(event.album).isEqualTo("Browsed Album")
        assertThat(event.cover).isEqualTo("https://gateway.example/cover/yt-live-browse")
    }

    @Test
    fun `recordPlayback falls back to the synced library when no metadata is supplied`() = runTest {
        val musicDao = mockk<MusicDao>(relaxed = true)
        val outbox = mockk<ListeningEventOutbox>(relaxed = true)
        val navidromeRepository = mockk<NavidromeRepository>(relaxed = true)
        val repository = createRepository(
            musicDao = musicDao,
            navidromeRepository = navidromeRepository,
            outbox = outbox
        )
        stubGatewayUpload(navidromeRepository)
        val enqueued = stubOutboxEnqueue(outbox)
        coEvery { musicDao.getSongByIdOnce(77L) } returns songEntity()

        repository.recordPlayback(
            songId = "77",
            durationMs = TimeUnit.SECONDS.toMillis(42),
            timestamp = 1_700_000_000_000L
        )

        val event = checkNotNull(enqueued.value)
        assertThat(event.navidromeId).isEqualTo("yt-synced")
        assertThat(event.title).isEqualTo("Synced Track")
        assertThat(event.artist).isEqualTo("Synced Artist")
        assertThat(event.album).isEqualTo("Synced Album")
        assertThat(event.cover).isEqualTo("https://gateway.example/cover/yt-synced")
    }

    /** Captures the event [PlaybackStatsRepository.recordPlayback] queues for upload. */
    private fun stubOutboxEnqueue(
        outbox: ListeningEventOutbox
    ): CapturedEvent {
        val captured = CapturedEvent()
        coEvery {
            outbox.enqueue(any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            ListeningEventOutbox.PendingListeningEvent(
                eventId = "event-1",
                navidromeId = arg<String>(0),
                title = arg<String>(1),
                artist = arg<String>(2),
                album = arg<String>(3),
                cover = arg<String>(4),
                durationMs = arg<Long>(5),
                startTimestamp = arg<Long>(6),
                endTimestamp = arg<Long>(7)
            ).also { captured.value = it }
        }
        return captured
    }

    private fun stubGatewayUpload(navidromeRepository: NavidromeRepository) {
        coEvery {
            navidromeRepository.reportListeningEvent(
                any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns Result.success(mockk<JSONObject>(relaxed = true))
    }

    private class CapturedEvent {
        var value: ListeningEventOutbox.PendingListeningEvent? = null
    }

    private fun songEntity(): SongEntity = SongEntity(
        id = 77L,
        title = "Synced Track",
        artistName = "Synced Artist",
        artistId = 1L,
        albumName = "Synced Album",
        albumId = 1L,
        contentUriString = "navidrome://yt-synced",
        albumArtUriString = "https://gateway.example/cover/yt-synced",
        duration = TimeUnit.MINUTES.toMillis(4),
        genre = null,
        filePath = "",
        parentDirectoryPath = ""
    )

    private fun createRepository(
        musicDao: MusicDao = mockk(relaxed = true),
        navidromeRepository: NavidromeRepository = mockk(relaxed = true),
        outbox: ListeningEventOutbox = mockk(relaxed = true)
    ): PlaybackStatsRepository {
        val uniqueDir = createTempDirectory(
            "playback-stats-test-${Instant.now().toEpochMilli()}-"
        ).toFile()
        val testContext = mockk<android.content.Context>(relaxed = true)
        every { testContext.filesDir } returns uniqueDir
        // These tests only exercise buildSummaryFromEvents (pure aggregation over an explicit
        // event list) — none of the gateway-backed dependencies are touched, so relaxed mocks
        // just need to satisfy the constructor.
        return PlaybackStatsRepository(
            context = testContext,
            musicDao = musicDao,
            navidromeRepository = navidromeRepository,
            outbox = outbox,
            workManager = mockk<WorkManager>(relaxed = true)
        )
    }

    private fun song(
        songId: String,
        durationMs: Long = 5 * 60 * 1000L,
        artist: String = "Artist",
        artists: List<ArtistRef> = emptyList(),
        genre: String? = null
    ): Song = Song(
        id = songId,
        title = "Song $songId",
        artist = artist,
        artistId = 1L,
        artists = artists,
        album = "Album",
        albumId = 1L,
        path = "/music/$songId.mp3",
        contentUriString = "content://media/external/audio/media/$songId",
        albumArtUriString = null,
        duration = durationMs,
        genre = genre,
        mimeType = "audio/mpeg",
        bitrate = 320_000,
        sampleRate = 44_100
    )
}
