package com.theveloper.pixelplay.presentation.viewmodel

import android.os.SystemClock
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.DailyMixManager
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.stats.PlaybackStatsRepository
import com.theveloper.pixelplay.data.stats.TrackMetadata
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ListeningStatsTrackerTest {

    private val dailyMixManager: DailyMixManager = mockk(relaxed = true)
    private val playbackStatsRepository: PlaybackStatsRepository = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        mockkStatic(SystemClock::class)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(SystemClock::class)
    }

    @Test
    fun `finalizeCurrentSession preserves listening longer than track duration`() {
        val tracker = ListeningStatsTracker(
            dailyMixManager = dailyMixManager,
            playbackStatsRepository = playbackStatsRepository
        )
        val song = song(
            songId = "looped-song",
            durationMs = TimeUnit.MINUTES.toMillis(3)
        )
        val listenedMs = TimeUnit.MINUTES.toMillis(12)

        every { SystemClock.elapsedRealtime() } returnsMany listOf(
            1_000L,
            1_000L + listenedMs
        )

        tracker.onSongChanged(
            song = song,
            positionMs = 0L,
            durationMs = song.duration,
            isPlaying = true
        )
        tracker.finalizeCurrentSession(forceSynchronousPersistence = true)

        coVerify(timeout = 2_000) {
            dailyMixManager.recordPlay(song.id, listenedMs, any())
        }
        coVerify(timeout = 2_000) {
            playbackStatsRepository.recordPlayback(song.id, listenedMs, any(), any())
        }
    }

    @Test
    fun `onProgress accumulates incremental listening time`() {
        val tracker = ListeningStatsTracker(
            dailyMixManager = dailyMixManager,
            playbackStatsRepository = playbackStatsRepository
        )
        val song = song(songId = "song-1")
        val firstChunkMs = 7_000L
        val secondChunkMs = 8_000L
        val expectedDurationMs = firstChunkMs + secondChunkMs

        every { SystemClock.elapsedRealtime() } returnsMany listOf(
            5_000L,
            5_000L + firstChunkMs,
            5_000L + firstChunkMs + secondChunkMs
        )

        tracker.onSongChanged(
            song = song,
            positionMs = 0L,
            durationMs = song.duration,
            isPlaying = true
        )
        tracker.onProgress(positionMs = firstChunkMs, isPlaying = true)
        tracker.finalizeCurrentSession(forceSynchronousPersistence = true)

        coVerify(timeout = 2_000) {
            playbackStatsRepository.recordPlayback(song.id, expectedDurationMs, any(), any())
        }
        assertThat(expectedDurationMs).isGreaterThan(TimeUnit.SECONDS.toMillis(5))
    }

    @Test
    fun `finalizeCurrentSession reports the metadata captured when the session opened`() {
        val tracker = ListeningStatsTracker(
            dailyMixManager = dailyMixManager,
            playbackStatsRepository = playbackStatsRepository
        )
        // A live-browsed gateway song: never synced, so the stats repository cannot look its
        // title/artist/album up locally and depends on what the session carries.
        val song = song(songId = "navidrome_yt-live-browse").copy(
            title = "Live Browsed Track",
            artist = "Browsed Artist",
            album = "Browsed Album",
            albumArtUriString = "https://gateway.example/cover/yt-live-browse"
        )
        val listenedMs = TimeUnit.SECONDS.toMillis(30)

        every { SystemClock.elapsedRealtime() } returnsMany listOf(
            1_000L,
            1_000L + listenedMs
        )

        tracker.onSongChanged(
            song = song,
            positionMs = 0L,
            durationMs = song.duration,
            isPlaying = true
        )
        tracker.finalizeCurrentSession(forceSynchronousPersistence = true)

        coVerify(timeout = 2_000) {
            playbackStatsRepository.recordPlayback(
                songId = song.id,
                durationMs = listenedMs,
                timestamp = any(),
                metadata = TrackMetadata(
                    title = "Live Browsed Track",
                    artist = "Browsed Artist",
                    album = "Browsed Album",
                    cover = "https://gateway.example/cover/yt-live-browse"
                )
            )
        }
    }

    @Test
    fun `ensureSession backfills metadata onto a session opened without any`() {
        val tracker = ListeningStatsTracker(
            dailyMixManager = dailyMixManager,
            playbackStatsRepository = playbackStatsRepository
        )
        val songId = "navidrome_yt-backfilled"
        val listenedMs = TimeUnit.SECONDS.toMillis(20)
        val metadata = TrackMetadata(
            title = "Backfilled Track",
            artist = "Backfilled Artist",
            album = "Backfilled Album",
            cover = "https://gateway.example/cover/yt-backfilled"
        )

        every { SystemClock.elapsedRealtime() } returnsMany listOf(
            2_000L,
            2_000L,
            2_000L + listenedMs
        )

        // The player sync that opens the session has no metadata to hand yet.
        tracker.onTrackChanged(
            songId = songId,
            positionMs = 0L,
            durationMs = TimeUnit.MINUTES.toMillis(4),
            isPlaying = true
        )
        tracker.ensureSession(
            songId = songId,
            positionMs = 0L,
            durationMs = TimeUnit.MINUTES.toMillis(4),
            isPlaying = true,
            metadata = metadata
        )
        tracker.finalizeCurrentSession(forceSynchronousPersistence = true)

        coVerify(timeout = 2_000) {
            playbackStatsRepository.recordPlayback(
                songId = songId,
                durationMs = listenedMs,
                timestamp = any(),
                metadata = metadata
            )
        }
    }

    private fun song(songId: String, durationMs: Long = 5 * 60 * 1000L): Song = Song(
        id = songId,
        title = "Song $songId",
        artist = "Artist",
        artistId = 1L,
        album = "Album",
        albumId = 1L,
        path = "/music/$songId.mp3",
        contentUriString = "content://media/external/audio/media/$songId",
        albumArtUriString = null,
        duration = durationMs,
        mimeType = "audio/mpeg",
        bitrate = 320_000,
        sampleRate = 44_100
    )
}
