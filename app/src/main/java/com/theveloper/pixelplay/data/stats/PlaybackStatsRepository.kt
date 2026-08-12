package com.theveloper.pixelplay.data.stats

import android.content.Context
import android.util.AtomicFile
import androidx.work.WorkManager
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.navidrome.NavidromeRepository
import com.theveloper.pixelplay.data.worker.ListeningEventUploadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import timber.log.Timber

@Singleton
class PlaybackStatsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicDao: MusicDao,
    private val navidromeRepository: NavidromeRepository,
    private val outbox: ListeningEventOutbox,
    private val workManager: WorkManager
) {

    private val gson = Gson()
    // Legacy local history file. No longer WRITTEN by recordPlayback()/loadSummary() (both now
    // go through the gateway — see fetchEventsForSummary()), but kept read/write-able as-is:
    // exportEventsForBackup()/importEventsFromBackup() still use it for local backup/restore,
    // and StatsHistoryMigrationWorker reads its frozen contents once, on upgrade, to seed the
    // gateway with each device's pre-existing history.
    private val historyFile = File(context.filesDir, "playback_history.json")
    private val atomicHistoryFile = AtomicFile(historyFile)
    private val fileLock = Any()
    private var cachedEvents: List<PlaybackEvent>? = null  // guarded by fileLock
    private val eventsType = object : TypeToken<MutableList<PlaybackEvent>>() {}.type
    private val _refreshVersion = MutableStateFlow(0L)
    val refreshFlow: StateFlow<Long> = _refreshVersion.asStateFlow()

    private val sessionGapThresholdMs = TimeUnit.MINUTES.toMillis(30)

    // ─── Gateway-backed listening events (the actual Stats data source) ────

    private val eventsCacheFile = File(context.filesDir, "listening_events_cache.json")
    private val atomicEventsCacheFile = AtomicFile(eventsCacheFile)
    private val cacheLock = Any()
    private val remoteEventsType = object : TypeToken<List<RemoteListeningEvent>>() {}.type

    /** Internal shape only — carries [eventId] (needed to dedupe against the outbox) through
     *  the fetch/merge step; stripped before handing events to [buildSummaryFromEvents]. */
    private data class RemoteListeningEvent(
        val eventId: String,
        val songId: String, // the app's unified Song.id, already translated from the gateway id
        val durationMs: Long,
        val startTimestamp: Long,
        val endTimestamp: Long
    )

    private fun RemoteListeningEvent.toPlaybackEvent() = PlaybackEvent(
        songId = songId,
        timestamp = endTimestamp,
        durationMs = durationMs,
        startTimestamp = startTimestamp,
        endTimestamp = endTimestamp
    )

    data class PlaybackEvent(
        val songId: String,
        val timestamp: Long,
        val durationMs: Long,
        val startTimestamp: Long? = null,
        val endTimestamp: Long? = null
    )

    data class PlaybackHistoryEntry(
        val songId: String,
        val timestamp: Long
    )

    data class SongPlaybackSummary(
        val songId: String,
        val title: String,
        val artist: String,
        val albumArtUri: String?,
        val totalDurationMs: Long,
        val playCount: Int
    )

    data class ArtistPlaybackSummary(
        val artist: String,
        val totalDurationMs: Long,
        val playCount: Int,
        val uniqueSongs: Int
    )

    data class GenrePlaybackSummary(
        val genre: String,
        val totalDurationMs: Long,
        val playCount: Int,
        val uniqueArtists: Int
    )

    data class AlbumPlaybackSummary(
        val album: String,
        val albumArtUri: String?,
        val totalDurationMs: Long,
        val playCount: Int,
        val uniqueSongs: Int
    )

    data class TimelineEntry(
        val label: String,
        val totalDurationMs: Long,
        val playCount: Int
    )

    data class PlaybackSegment(
        val songId: String,
        val startMillis: Long,
        val endMillis: Long
    ) {
        val durationMs: Long
            get() = (endMillis - startMillis).coerceAtLeast(0L)
    }

    data class PlaybackSpan(
        val startMillis: Long,
        val endMillis: Long
    ) {
        val durationMs: Long
            get() = (endMillis - startMillis).coerceAtLeast(0L)
    }

    data class DayListeningDistribution(
        val bucketSizeMinutes: Int,
        val buckets: List<DailyListeningBucket>,
        val maxBucketDurationMs: Long,
        val days: List<DailyListeningDay>
    )

    data class DailyListeningBucket(
        val startMinute: Int,
        val endMinuteExclusive: Int,
        val totalDurationMs: Long
    )

    data class DailyListeningDay(
        val date: LocalDate,
        val buckets: List<DailyListeningBucket>,
        val totalDurationMs: Long
    )

    data class PlaybackStatsSummary(
        val range: StatsTimeRange,
        val startTimestamp: Long?,
        val endTimestamp: Long,
        val totalDurationMs: Long,
        val totalPlayCount: Int,
        val uniqueSongs: Int,
        val averageDailyDurationMs: Long,
        val songs: List<SongPlaybackSummary> = emptyList(),
        val topSongs: List<SongPlaybackSummary> = emptyList(),
        val topGenres: List<GenrePlaybackSummary> = emptyList(),
        val timeline: List<TimelineEntry>,
        val topArtists: List<ArtistPlaybackSummary>,
        val topAlbums: List<AlbumPlaybackSummary>,
        val activeDays: Int,
        val longestStreakDays: Int,
        val totalSessions: Int,
        val averageSessionDurationMs: Long,
        val longestSessionDurationMs: Long,
        val averageSessionsPerDay: Double,
        val dayListeningDistribution: DayListeningDistribution?,
        val peakTimeline: TimelineEntry?,
        val peakDayLabel: String?,
        val peakDayDurationMs: Long
    )

    /** Resolves [songId] (either shape — see below) to a gateway song id, queues a durable
     *  listening event for it, and best-effort-uploads it immediately. Non-gateway songs
     *  (nothing resolvable either way) are a silent no-op — gateway-only scope, matching what
     *  the Stats page now reads back. */
    suspend fun recordPlayback(
        songId: String,
        durationMs: Long,
        timestamp: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        if (songId.isBlank()) return@withContext
        val coercedTimestamp = timestamp.coerceAtLeast(0L)
        val coercedDuration = durationMs.coerceAtLeast(0L)
        val start = (coercedTimestamp - coercedDuration).coerceAtLeast(0L)

        // Song.id reaches here in two shapes. Most plays are songs synced into the unified
        // library (NavidromeRepository.syncUnifiedLibrarySongsFromNavidrome) — a hashed Long,
        // resolvable via MusicDao, which also gives real title/artist/album/cover. But anything
        // surfaced by live gateway browsing that was never persisted there — search results,
        // "Browse by genre", similar-songs/radio, curated home rows — carries a
        // "navidrome_<rawId>" string instead (see NavidromeSong.toSong()); MusicDao has never
        // heard of it. Handle both: try the synced-library path first, then fall back to
        // stripping the live-browse prefix. The stripped remainder is only trusted if it still
        // looks like a real gateway song id (starts with "yt-") — a synced-*playlist* song's id
        // is a different, composite shape ("navidrome_<playlistId>_<rawId>") that stripping
        // alone can't safely recover, so that narrower case is left unresolved rather than
        // risking a garbled id.
        val song = songId.toLongOrNull()?.let { musicDao.getSongByIdOnce(it) }
        val navidromeId = song?.contentUriString
            ?.takeIf { it.startsWith(NAVIDROME_URI_PREFIX) }
            ?.removePrefix(NAVIDROME_URI_PREFIX)
            ?.takeIf { it.isNotBlank() }
            ?: songId.removePrefix(LIVE_GATEWAY_ID_PREFIX)
                .takeIf { it != songId && it.startsWith(GATEWAY_SONG_ID_PREFIX) }
            ?: return@withContext

        val pending = outbox.enqueue(
            navidromeId = navidromeId,
            // Metadata is best-effort and only for the server's own record: nothing client-side
            // reads it back — loadSummary always re-resolves title/artist/album/cover from its
            // own `songs` list, joined by unified id (see buildSummaryFromEvents). A live-browse
            // song has no local SongEntity to pull this from, so it's sent blank.
            title = song?.title.orEmpty(),
            artist = song?.artistName.orEmpty(),
            album = song?.albumName.orEmpty(),
            cover = song?.albumArtUriString.orEmpty(),
            durationMs = coercedDuration,
            startTimestamp = start,
            endTimestamp = coercedTimestamp
        )
        notifyStatsChanged() // reflect the play in the UI before the network round-trip lands

        val uploaded = navidromeRepository.reportListeningEvent(
            eventId = pending.eventId,
            songId = pending.navidromeId,
            title = pending.title,
            artist = pending.artist,
            album = pending.album,
            cover = pending.cover,
            durationMs = pending.durationMs,
            startTime = pending.startTimestamp,
            endTime = pending.endTimestamp
        )
        if (uploaded.isSuccess) {
            outbox.remove(listOf(pending.eventId))
        } else {
            ListeningEventUploadWorker.enqueue(workManager)
        }
    }

    suspend fun loadSummary(
        range: StatsTimeRange,
        songs: List<Song>,
        nowMillis: Long = System.currentTimeMillis()
    ): PlaybackStatsSummary = withContext(Dispatchers.IO) {
        buildSummaryFromEvents(
            range = range,
            songs = songs,
            nowMillis = nowMillis,
            allEvents = fetchEventsForSummary(),
            zoneId = ZoneId.systemDefault()
        )
    }

    /** Not logged into the gateway -> the empty-history summary buildSummaryFromEvents already
     *  produces for a blank event list (same shape StatsScreen already renders for "no plays
     *  yet"). Logged in -> merge the gateway's confirmed events with anything still in the
     *  local outbox (so a play made offline shows up in your own stats immediately), falling
     *  back to the last successful fetch if the network call itself fails. */
    private suspend fun fetchEventsForSummary(): List<PlaybackEvent> {
        if (!navidromeRepository.isLoggedIn) return emptyList()

        val pendingEvents = outbox.pending().map { toRemoteEvent(it) }
        val remoteEvents = navidromeRepository.getListeningEvents().fold(
            onSuccess = { payload ->
                parseRemoteEvents(payload).also { cacheRemoteEvents(it) }
            },
            onFailure = { readCachedRemoteEvents() }
        )

        // Prefer the confirmed-remote copy of an event; an outbox entry whose upload actually
        // succeeded but whose remove() hasn't landed yet shouldn't double-count.
        val merged = LinkedHashMap<String, RemoteListeningEvent>()
        remoteEvents.forEach { merged[it.eventId] = it }
        pendingEvents.forEach { merged.putIfAbsent(it.eventId, it) }
        return merged.values.map { it.toPlaybackEvent() }
    }

    private fun toRemoteEvent(pending: ListeningEventOutbox.PendingListeningEvent) =
        RemoteListeningEvent(
            eventId = pending.eventId,
            songId = navidromeRepository.toUnifiedSongId(pending.navidromeId).toString(),
            durationMs = pending.durationMs,
            startTimestamp = pending.startTimestamp,
            endTimestamp = pending.endTimestamp
        )

    private fun parseRemoteEvents(payload: JSONObject): List<RemoteListeningEvent> {
        val array = payload.optJSONArray("event") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val eventId = obj.optString("eventId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val gatewaySongId = obj.optString("songId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val durationMs = obj.optLong("durationMs", 0L)
            if (durationMs <= 0L) return@mapNotNull null
            RemoteListeningEvent(
                eventId = eventId,
                songId = navidromeRepository.toUnifiedSongId(gatewaySongId).toString(),
                durationMs = durationMs,
                startTimestamp = obj.optLong("startTime", 0L),
                endTimestamp = obj.optLong("endTime", 0L)
            )
        }
    }

    private fun readCachedRemoteEvents(): List<RemoteListeningEvent> {
        val raw = synchronized(cacheLock) {
            runCatching {
                atomicEventsCacheFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
                .onFailure { throwable ->
                    if (throwable !is FileNotFoundException) {
                        Timber.e(throwable, "Failed reading cached listening events")
                    }
                }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
        } ?: return emptyList()
        return runCatching { gson.fromJson<List<RemoteListeningEvent>>(raw, remoteEventsType) ?: emptyList() }
            .getOrElse { throwable ->
                Timber.e(throwable, "Failed parsing cached listening events")
                emptyList()
            }
    }

    private fun cacheRemoteEvents(events: List<RemoteListeningEvent>) {
        synchronized(cacheLock) {
            var outputStream: FileOutputStream? = null
            runCatching {
                val parent = eventsCacheFile.parentFile
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    Timber.w("Unable to ensure listening events cache directory: ${parent.absolutePath}")
                }
                outputStream = atomicEventsCacheFile.startWrite()
                outputStream?.write(gson.toJson(events).toByteArray(Charsets.UTF_8))
                outputStream?.fd?.sync()
                outputStream?.let { atomicEventsCacheFile.finishWrite(it) }
                outputStream = null
            }.onFailure { throwable ->
                outputStream?.let { stream -> atomicEventsCacheFile.failWrite(stream) }
                Timber.e(throwable, "Failed to cache listening events")
            }
        }
    }

    internal fun buildSummaryFromEvents(
        range: StatsTimeRange,
        songs: List<Song>,
        nowMillis: Long,
        allEvents: List<PlaybackEvent>,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): PlaybackStatsSummary {
        val (startBound, endBound) = range.resolveBounds(allEvents, nowMillis, zoneId)
        val filteredEvents = allEvents.mapNotNull { event ->
            val start = event.startMillis()
            val end = event.endMillis()
            val lowerBound = startBound ?: Long.MIN_VALUE
            if (end < lowerBound || start > endBound) {
                return@mapNotNull null
            }

            val clippedStart = max(start, lowerBound)
            val clippedEnd = min(end, endBound)
            val clippedDuration = (clippedEnd - clippedStart).coerceAtLeast(0L)
            if (clippedDuration <= 0L) {
                return@mapNotNull null
            }

            event.copy(
                timestamp = clippedEnd,
                durationMs = clippedDuration,
                startTimestamp = clippedStart,
                endTimestamp = clippedEnd
            )
        }

        val songMap = songs.associateBy { it.id }
        val normalizedEvents = filteredEvents

        val segmentsBySong = normalizedEvents
            .groupBy { it.songId }
            .mapValues { (_, eventsForSong) -> mergeSongEvents(eventsForSong) }

        val overallSpans = mergeSpans(segmentsBySong.values.flatten().map { PlaybackSpan(it.startMillis, it.endMillis) })

        val effectiveStart = startBound
            ?: overallSpans.minOfOrNull { it.startMillis }
            ?: normalizedEvents.minOfOrNull { it.startMillis() }
            ?: allEvents.minOfOrNull { it.startMillis() }
        val effectiveEnd = overallSpans.maxOfOrNull { it.endMillis } ?: endBound

        val totalDuration = overallSpans.sumOf { it.durationMs }
        val totalPlays = segmentsBySong.values.sumOf { it.size }
        val uniqueSongs = segmentsBySong.keys.size

        val allSongs = segmentsBySong
            .mapNotNull { (songId, segmentsForSong) ->
                val song = songMap[songId] ?: return@mapNotNull null
                val title = song.title.takeIf { it.isNotBlank() }
                    ?: song.path.substringAfterLast('/').ifBlank { return@mapNotNull null }
                val artist = song.displayArtist.takeIf { it.isNotBlank() } ?: "Unknown Artist"
                SongPlaybackSummary(
                    songId = songId,
                    title = title,
                    artist = artist,
                    albumArtUri = song.albumArtUriString,
                    totalDurationMs = segmentsForSong.sumOf { it.durationMs },
                    playCount = segmentsForSong.size
                )
            }
            .sortedWith(
                compareByDescending<SongPlaybackSummary> { it.totalDurationMs }
                    .thenByDescending { it.playCount }
            )
            .take(MAX_SONG_STATS_COUNT)
        val topSongs = allSongs.take(5)

        val topGenres = segmentsBySong.entries
            .groupBy { (songId, _) ->
                val genre = songMap[songId]?.genre
                if (genre.isNullOrBlank()) "Unknown Genre" else genre
            }
            .map { (genre, groupedSongs) ->
                val flattened = groupedSongs.flatMap { it.value }
                val uniqueArtists = groupedSongs
                    .flatMap { (songId, _) ->
                        statsArtistNames(songMap[songId])
                    }
                    .distinctBy { it.normalizedArtistKey() }
                    .size
                GenrePlaybackSummary(
                    genre = genre,
                    totalDurationMs = flattened.sumOf { it.durationMs },
                    playCount = flattened.size,
                    uniqueArtists = uniqueArtists
                )
            }
            .sortedWith(
                compareByDescending<GenrePlaybackSummary> { it.totalDurationMs }
                    .thenByDescending { it.playCount }
            )
            .take(5)

        var daySpan = 1L
        val averageDailyDuration = if (effectiveStart != null) {
            val startInstant = Instant.ofEpochMilli(effectiveStart)
            val endInstant = Instant.ofEpochMilli(effectiveEnd)
            val startDate = startInstant.atZone(zoneId).toLocalDate()
            val endDate = endInstant.atZone(zoneId).toLocalDate()
            daySpan = max(1L, java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1)
            if (daySpan > 0) totalDuration / daySpan else totalDuration
        } else {
            totalDuration
        }

        val daySlices = overallSpans.flatMap { sliceSpanByDay(it, zoneId) }
        val eventsByDay = daySlices.groupBy { it.date }
        val activeDays = eventsByDay.size
        val sortedDays = eventsByDay.keys.sorted()
        var longestStreak = 0
        var currentStreak = 0
        var lastDay: java.time.LocalDate? = null
        sortedDays.forEach { day ->
            if (lastDay == null || day == lastDay.plusDays(1)) {
                currentStreak += 1
            } else {
                currentStreak = 1
            }
            if (currentStreak > longestStreak) {
                longestStreak = currentStreak
            }
            lastDay = day
        }

        val sessions = computeListeningSessions(overallSpans)
        val totalSessions = sessions.size
        val totalSessionDuration = sessions.sumOf { it.totalDuration }
        val averageSessionDuration = if (totalSessions > 0) totalSessionDuration / totalSessions else 0L
        val longestSessionDuration = sessions.maxOfOrNull { it.totalDuration } ?: 0L
        val averageSessionsPerDay = if (daySpan > 0) totalSessions.toDouble() / daySpan else 0.0

        val timelineBuckets = createTimelineBuckets(
            range = range,
            zoneId = zoneId,
            now = Instant.ofEpochMilli(endBound),
            spans = overallSpans,
            fallbackStart = effectiveStart ?: endBound
        )
        val timelineEntries = accumulateTimelineEntries(timelineBuckets, overallSpans)

        val topArtists = segmentsBySong.entries
            .flatMap { (songId, segmentsForSong) ->
                statsArtistNames(songMap[songId]).map { artist ->
                    ArtistSongPlayback(
                        artist = artist,
                        songId = songId,
                        segments = segmentsForSong
                    )
                }
            }
            .groupBy { it.artist }
            .map { (artist, artistSongs) ->
                val flattened = artistSongs.flatMap { it.segments }
                val uniqueSongCount = artistSongs
                    .map { it.songId }
                    .toSet()
                    .size
                ArtistPlaybackSummary(
                    artist = artist,
                    totalDurationMs = flattened.sumOf { it.durationMs },
                    playCount = flattened.size,
                    uniqueSongs = uniqueSongCount
                )
            }
            .sortedWith(
                compareByDescending<ArtistPlaybackSummary> { it.totalDurationMs }
                    .thenByDescending { it.playCount }
            )
            .take(5)

        val topAlbums = segmentsBySong.entries
            .groupBy { (songId, _) ->
                val song = songMap[songId]
                song?.album?.takeIf { it.isNotBlank() } ?: "Unknown Album"
            }
            .map { (album, groupedSongs) ->
                val flattened = groupedSongs.flatMap { it.value }
                val uniqueSongCount = groupedSongs.size
                val firstSong = groupedSongs
                    .asSequence()
                    .mapNotNull { songMap[it.key] }
                    .firstOrNull()
                AlbumPlaybackSummary(
                    album = album,
                    albumArtUri = firstSong?.albumArtUriString,
                    totalDurationMs = flattened.sumOf { it.durationMs },
                    playCount = flattened.size,
                    uniqueSongs = uniqueSongCount
                )
            }
            .sortedWith(
                compareByDescending<AlbumPlaybackSummary> { it.totalDurationMs }
                    .thenByDescending { it.playCount }
            )
            .take(5)

        val peakTimeline = timelineEntries
            .filter { it.totalDurationMs > 0L }
            .maxByOrNull { it.totalDurationMs }

        val durationsByDayOfWeek = daySlices.groupBy { slice ->
            slice.date.dayOfWeek
        }
        val peakDay = durationsByDayOfWeek.maxByOrNull { entry ->
            entry.value.sumOf { it.durationMs }
        }
        val peakDayLabel = peakDay?.key?.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val peakDayDuration = peakDay?.value?.sumOf { it.durationMs } ?: 0L
        val dayListeningDistribution = if (range == StatsTimeRange.DAY || range == StatsTimeRange.WEEK) {
            computeDayListeningDistribution(
                spans = overallSpans,
                zoneId = zoneId,
                range = range,
                startBound = startBound,
                endBound = endBound
            )
        } else null

        return PlaybackStatsSummary(
            range = range,
            startTimestamp = startBound,
            endTimestamp = endBound,
            totalDurationMs = totalDuration,
            totalPlayCount = totalPlays,
            uniqueSongs = uniqueSongs,
            averageDailyDurationMs = averageDailyDuration,
            songs = allSongs,
            topSongs = topSongs,
            timeline = timelineEntries,
            topGenres = topGenres,
            topArtists = topArtists,
            topAlbums = topAlbums,
            activeDays = activeDays,
            longestStreakDays = longestStreak,
            totalSessions = totalSessions,
            averageSessionDurationMs = averageSessionDuration,
            longestSessionDurationMs = longestSessionDuration,
            averageSessionsPerDay = averageSessionsPerDay,
            dayListeningDistribution = dayListeningDistribution,
            peakTimeline = peakTimeline,
            peakDayLabel = peakDayLabel,
            peakDayDurationMs = peakDayDuration
        )
    }

    suspend fun exportEventsForBackup(): List<PlaybackEvent> = withContext(Dispatchers.IO) {
        readEvents().map { event -> sanitizeEvent(event) }
    }

    suspend fun loadPlaybackHistory(limit: Int = DEFAULT_PLAYBACK_HISTORY_LIMIT): List<PlaybackHistoryEntry> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()
        val safeLimit = limit.coerceAtMost(MAX_PLAYBACK_HISTORY_LIMIT)
        fetchEventsForSummary()
            .asSequence()
            .sortedByDescending { event -> event.timestamp }
            .take(safeLimit)
            .map { event ->
                PlaybackHistoryEntry(
                    songId = event.songId,
                    timestamp = event.timestamp.coerceAtLeast(0L)
                )
            }
            .toList()
    }

    suspend fun importEventsFromBackup(
        events: List<PlaybackEvent>,
        clearExisting: Boolean = true
    ) = withContext(Dispatchers.IO) {
        val writeSucceeded = updateEventsAtomically { existingEvents ->
            val base = if (clearExisting) {
                emptyList()
            } else {
                existingEvents
            }
            val merged = (base + events)
                .map { event -> sanitizeEvent(event) }
                .distinctBy { event ->
                    "${event.songId}:${event.startMillis()}:${event.endMillis()}:${event.durationMs}"
                }
                .sortedBy { event -> event.timestamp }
                .toMutableList()
            merged
        }
        if (writeSucceeded) {
            notifyStatsChanged()
        }
    }

    fun requestRefresh() {
        notifyStatsChanged()
    }

    private fun readEvents(): List<PlaybackEvent> {
        synchronized(fileLock) { cachedEvents }?.let { return it }
        val raw = synchronized(fileLock) { readRawHistoryLocked() }
        return parseEvents(raw).also { parsed ->
            synchronized(fileLock) { if (cachedEvents == null) cachedEvents = parsed }
        }
    }

    private fun readRawHistoryLocked(): String? {
        return runCatching {
            atomicHistoryFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
            .onFailure { throwable ->
                if (throwable !is FileNotFoundException) {
                    Timber.e(throwable, "Failed reading playback history")
                }
            }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun parseEvents(raw: String?): MutableList<PlaybackEvent> {
        if (raw.isNullOrBlank()) {
            return mutableListOf()
        }

        return runCatching {
            val element = gson.fromJson(raw, JsonElement::class.java)
            parseElement(element)
        }.getOrElse { throwable ->
            Timber.e(throwable, "Failed parsing playback history")
            mutableListOf()
        }
    }

    private fun parseElement(element: JsonElement?): MutableList<PlaybackEvent> {
        if (element == null || element.isJsonNull) return mutableListOf()
        if (element.isJsonArray) {
            val parsed: MutableList<PlaybackEvent> = gson.fromJson(element, eventsType)
            return parsed.mapTo(mutableListOf()) { sanitizeEvent(it) }
        }
        return mutableListOf()
    }

    private fun sanitizeEvent(event: PlaybackEvent): PlaybackEvent {
        val safeDuration = event.durationMs.coerceAtLeast(0L)
        val safeEnd = (event.endTimestamp ?: event.timestamp).coerceAtLeast(0L)
        val safeStart = when {
            event.startTimestamp != null -> event.startTimestamp.coerceIn(0L, safeEnd)
            safeDuration > 0L -> (safeEnd - safeDuration).coerceAtLeast(0L)
            else -> safeEnd
        }
        val normalizedDuration = (safeEnd - safeStart).coerceAtLeast(0L)
        val finalDuration = when {
            event.startTimestamp == null && safeDuration in 1 until normalizedDuration -> safeDuration
            else -> normalizedDuration
        }
        val finalStart = (safeEnd - finalDuration).coerceAtLeast(0L)
        return event.copy(
            songId = event.songId,
            timestamp = safeEnd,
            durationMs = finalDuration,
            startTimestamp = finalStart,
            endTimestamp = safeEnd
        )
    }

    private fun mergeSongEvents(events: List<PlaybackEvent>): List<PlaybackSegment> {
        if (events.isEmpty()) return emptyList()
        val sorted = events.sortedBy { it.startMillis() }
        val songId = sorted.first().songId
        val segments = mutableListOf<PlaybackSegment>()
        var currentStart = sorted.first().startMillis()
        var currentEnd = sorted.first().endMillis()
        for (index in 1 until sorted.size) {
            val event = sorted[index]
            val start = event.startMillis()
            val end = event.endMillis()
            if (start <= currentEnd + SEGMENT_JOIN_TOLERANCE_MS) {
                currentEnd = max(currentEnd, end)
            } else {
                segments += PlaybackSegment(songId, currentStart, currentEnd)
                currentStart = start
                currentEnd = end
            }
        }
        segments += PlaybackSegment(songId, currentStart, currentEnd)
        return segments
    }

    private fun mergeSpans(spans: List<PlaybackSpan>): List<PlaybackSpan> {
        if (spans.isEmpty()) return emptyList()
        val sorted = spans.sortedBy { it.startMillis }
        val merged = mutableListOf<PlaybackSpan>()
        var currentStart = sorted.first().startMillis
        var currentEnd = sorted.first().endMillis
        for (index in 1 until sorted.size) {
            val span = sorted[index]
            val start = span.startMillis
            val end = span.endMillis
            if (start <= currentEnd + SEGMENT_JOIN_TOLERANCE_MS) {
                currentEnd = max(currentEnd, end)
            } else {
                merged += PlaybackSpan(currentStart, currentEnd)
                currentStart = start
                currentEnd = end
            }
        }
        merged += PlaybackSpan(currentStart, currentEnd)
        return merged
    }

    private data class DaySlice(
        val date: LocalDate,
        val durationMs: Long
    )

    private data class ArtistSongPlayback(
        val artist: String,
        val songId: String,
        val segments: List<PlaybackSegment>
    )

    private fun statsArtistNames(song: Song?): List<String> {
        if (song == null) return listOf(UNKNOWN_ARTIST)

        val separatedArtists = song.artists
            .sortedByDescending { it.isPrimary }
            .map { it.name.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.normalizedArtistKey() }
        if (separatedArtists.isNotEmpty()) {
            return separatedArtists
        }

        val fallbackArtist = song.displayArtist.trim()
        return listOf(fallbackArtist.ifBlank { UNKNOWN_ARTIST })
    }

    private fun String.normalizedArtistKey(): String = trim().lowercase(Locale.ROOT)

    private fun sliceSpanByDay(span: PlaybackSpan, zoneId: ZoneId): List<DaySlice> {
        if (span.durationMs <= 0L) return emptyList()
        val slices = mutableListOf<DaySlice>()
        var cursor = span.startMillis
        val end = span.endMillis
        while (cursor < end) {
            val zoned = Instant.ofEpochMilli(cursor).atZone(zoneId)
            val dayStart = zoned.toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli()
            val nextDayStart = zoned.toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val sliceEnd = min(end, nextDayStart)
            val sliceDuration = (sliceEnd - cursor).coerceAtLeast(0L)
            if (sliceDuration > 0L) {
                slices += DaySlice(zoned.toLocalDate(), sliceDuration)
            }
            cursor = sliceEnd
        }
        return slices
    }

    private fun computeDayListeningDistribution(
        spans: List<PlaybackSpan>,
        zoneId: ZoneId,
        range: StatsTimeRange,
        startBound: Long?,
        endBound: Long,
        bucketSizeMinutes: Int = 5
    ): DayListeningDistribution? {
        if (spans.isEmpty()) return null
        val bucketDurationMs = TimeUnit.MINUTES.toMillis(bucketSizeMinutes.toLong())
        val minutesPerDay = TimeUnit.DAYS.toMinutes(1)
        val bucketCount = (minutesPerDay / bucketSizeMinutes).toInt().coerceAtLeast(1)
        val totals = LongArray(bucketCount)
        val totalsByDay = mutableMapOf<LocalDate, LongArray>()
        spans.forEach { span ->
            var cursor = span.startMillis
            val end = span.endMillis
            while (cursor < end) {
                val zoned = Instant.ofEpochMilli(cursor).atZone(zoneId)
                val day = zoned.toLocalDate()
                val dayStart = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
                var bucketIndex = ((cursor - dayStart) / bucketDurationMs).toInt()
                if (bucketIndex >= bucketCount) {
                    bucketIndex = bucketCount - 1
                } else if (bucketIndex < 0) {
                    bucketIndex = 0
                }
                val bucketStart = dayStart + bucketIndex * bucketDurationMs
                val bucketEnd = min(end, bucketStart + bucketDurationMs)
                val contribution = (bucketEnd - cursor).coerceAtLeast(0L)
                if (contribution > 0L) {
                    totals[bucketIndex] += contribution
                    val dayTotals = totalsByDay.getOrPut(day) { LongArray(bucketCount) }
                    dayTotals[bucketIndex] += contribution
                }
                cursor = if (bucketEnd > cursor) bucketEnd else end
            }
        }
        val buckets = buildList {
            for (index in 0 until bucketCount) {
                val durationMs = totals[index]
                if (durationMs > 0L) {
                    add(
                        DailyListeningBucket(
                            startMinute = index * bucketSizeMinutes,
                            endMinuteExclusive = (index + 1) * bucketSizeMinutes,
                            totalDurationMs = durationMs
                        )
                    )
                }
            }
        }
        if (buckets.isEmpty()) return null
        val maxBucketDuration = buckets.maxOf { it.totalDurationMs }.coerceAtLeast(0L)

        val daySequence: List<LocalDate> = when (range) {
            StatsTimeRange.DAY -> {
                val anchor = startBound?.let { Instant.ofEpochMilli(it) }
                    ?: spans.minOfOrNull { Instant.ofEpochMilli(it.startMillis) }
                    ?: Instant.ofEpochMilli(endBound)
                listOf(anchor.atZone(zoneId).toLocalDate())
            }
            StatsTimeRange.WEEK -> {
                val anchor = startBound?.let { Instant.ofEpochMilli(it) }
                    ?: spans.minOfOrNull { Instant.ofEpochMilli(it.startMillis) }
                    ?: Instant.ofEpochMilli(endBound)
                val startDate = anchor.atZone(zoneId).toLocalDate()
                (0 until 7).map { offset -> startDate.plusDays(offset.toLong()) }
            }
            else -> totalsByDay.keys.sorted()
        }

        val days = daySequence.map { date ->
            val bucketTotals = totalsByDay[date]
            val dayBuckets = buildList {
                if (bucketTotals != null) {
                    for (index in 0 until bucketCount) {
                        val duration = bucketTotals[index]
                        if (duration > 0L) {
                            add(
                                DailyListeningBucket(
                                    startMinute = index * bucketSizeMinutes,
                                    endMinuteExclusive = (index + 1) * bucketSizeMinutes,
                                    totalDurationMs = duration
                                )
                            )
                        }
                    }
                }
            }
            val totalDuration = bucketTotals?.sumOf { it } ?: 0L
            DailyListeningDay(
                date = date,
                buckets = dayBuckets,
                totalDurationMs = totalDuration
            )
        }

        return DayListeningDistribution(
            bucketSizeMinutes = bucketSizeMinutes,
            buckets = buckets,
            maxBucketDurationMs = maxBucketDuration,
            days = days
        )
    }

    private data class ListeningSessionAggregate(
        var start: Long,
        var end: Long,
        var totalDuration: Long,
        var playCount: Int
    )

    private fun computeListeningSessions(spans: List<PlaybackSpan>): List<ListeningSessionAggregate> {
        if (spans.isEmpty()) return emptyList()
        val sorted = spans.sortedBy { it.startMillis }
        val sessions = mutableListOf<ListeningSessionAggregate>()

        var current = ListeningSessionAggregate(
            start = sorted.first().startMillis,
            end = sorted.first().endMillis,
            totalDuration = sorted.first().durationMs,
            playCount = 1
        )

        for (index in 1 until sorted.size) {
            val span = sorted[index]
            val spanStart = span.startMillis
            val spanEnd = span.endMillis
            val gap = spanStart - current.end
            if (gap <= sessionGapThresholdMs) {
                current.end = max(current.end, spanEnd)
                current.totalDuration += span.durationMs
                current.playCount += 1
            } else {
                sessions += current
                current = ListeningSessionAggregate(
                    start = spanStart,
                    end = spanEnd,
                    totalDuration = span.durationMs,
                    playCount = 1
                )
            }
        }

        sessions += current
        return sessions
    }

    private fun updateEventsAtomically(
        transform: (MutableList<PlaybackEvent>) -> MutableList<PlaybackEvent>
    ): Boolean {
        repeat(MAX_FILE_UPDATE_RETRIES) {
            val rawSnapshot = synchronized(fileLock) { readRawHistoryLocked() }
            val updatedEvents = transform(parseEvents(rawSnapshot))
            val payload = serializeEvents(updatedEvents)

            val writeSucceeded = synchronized(fileLock) {
                val latestRaw = readRawHistoryLocked()
                if (latestRaw != rawSnapshot) {
                    return@synchronized false
                }
                val result = writePayloadLocked(payload)
                if (result) cachedEvents = null
                result
            }
            if (writeSucceeded) {
                return true
            }
        }

        val fallbackRawSnapshot = synchronized(fileLock) { readRawHistoryLocked() }
        val payload = serializeEvents(transform(parseEvents(fallbackRawSnapshot)))
        return synchronized(fileLock) {
            val result = writePayloadLocked(payload)
            if (result) cachedEvents = null
            result
        }
    }

    private fun serializeEvents(events: List<PlaybackEvent>): ByteArray {
        val sanitized = events.map { sanitizeEvent(it) }
        return gson.toJson(sanitized).toByteArray(Charsets.UTF_8)
    }

    private fun writePayloadLocked(payload: ByteArray): Boolean {
        var outputStream: FileOutputStream? = null
        return runCatching {
            val parent = historyFile.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Timber.w("Unable to ensure playback history directory: ${parent.absolutePath}")
            }
            outputStream = atomicHistoryFile.startWrite()
            outputStream?.write(payload)
            outputStream?.fd?.sync()
            outputStream?.let { atomicHistoryFile.finishWrite(it) }
            outputStream = null
            true
        }.onFailure { throwable ->
            outputStream?.let { stream -> atomicHistoryFile.failWrite(stream) }
            Timber.e(throwable, "Failed to persist playback history")
        }.getOrDefault(false)
    }

    private fun accumulateTimelineEntries(
        buckets: List<TimelineBucket>,
        spans: List<PlaybackSpan>
    ): List<TimelineEntry> {
        if (buckets.isEmpty()) return emptyList()
        val durationByBucket = LongArray(buckets.size)
        val playCountByBucket = DoubleArray(buckets.size)
        spans.forEach { span ->
            val spanStart = span.startMillis
            val spanEnd = span.endMillis
            val spanDuration = span.durationMs
            if (spanDuration <= 0L) return@forEach
            buckets.forEachIndexed { index, bucket ->
                val bucketEndExclusive = if (bucket.inclusiveEnd) bucket.endMillis + 1 else bucket.endMillis
                val overlapStart = max(spanStart, bucket.startMillis)
                val overlapEnd = min(spanEnd, bucketEndExclusive)
                val overlap = (overlapEnd - overlapStart).coerceAtLeast(0L)
                if (overlap > 0) {
                    durationByBucket[index] += overlap
                    playCountByBucket[index] += overlap.toDouble() / spanDuration.toDouble()
                }
            }
        }
        return buckets.mapIndexed { index, bucket ->
            TimelineEntry(
                label = bucket.label,
                totalDurationMs = durationByBucket[index],
                playCount = playCountByBucket[index].roundToInt()
            )
        }
    }

    private fun createTimelineBuckets(
        range: StatsTimeRange,
        zoneId: ZoneId,
        now: Instant,
        spans: List<PlaybackSpan>,
        fallbackStart: Long
    ): List<TimelineBucket> {
        return when (range) {
            StatsTimeRange.DAY -> createDayBuckets(zoneId, now)
            StatsTimeRange.WEEK -> createWeekBuckets(zoneId, now)
            StatsTimeRange.MONTH -> createMonthBuckets(zoneId, now)
            StatsTimeRange.YEAR -> createYearBuckets(zoneId, now)
            StatsTimeRange.ALL -> createAllTimeBuckets(zoneId, spans, fallbackStart, now)
        }
    }

    private fun createDayBuckets(zoneId: ZoneId, now: Instant): List<TimelineBucket> {
        val dayStart = now.atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant()
        val formatter = DateTimeFormatter.ofPattern("ha", Locale.US)
        return (0 until 6).map { index ->
            val bucketStart = dayStart.plus(Duration.ofHours((index * 4).toLong()))
            val bucketEnd = bucketStart.plus(Duration.ofHours(4))
            val label = formatter.format(bucketStart.atZone(zoneId)).lowercase(Locale.US)
            TimelineBucket(
                label = label,
                startMillis = bucketStart.toEpochMilli(),
                endMillis = bucketEnd.toEpochMilli(),
                inclusiveEnd = false
            )
        }
    }

    private fun createWeekBuckets(zoneId: ZoneId, now: Instant): List<TimelineBucket> {
        val startOfWeek = now.atZone(zoneId)
            .toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (0 until 7).map { index ->
            val day = startOfWeek.plusDays(index.toLong())
            val start = day.atStartOfDay(zoneId).toInstant()
            val end = day.plusDays(1).atStartOfDay(zoneId).toInstant()
            TimelineBucket(
                label = day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                startMillis = start.toEpochMilli(),
                endMillis = end.toEpochMilli(),
                inclusiveEnd = false
            )
        }
    }

    private fun createMonthBuckets(zoneId: ZoneId, now: Instant): List<TimelineBucket> {
        val yearMonth = YearMonth.from(now.atZone(zoneId))
        val daysInMonth = yearMonth.lengthOfMonth()
        val bucketCount = 4
        return buildList {
            repeat(bucketCount) { index ->
                val startDay = index * 7 + 1
                if (startDay > daysInMonth) {
                    return@repeat
                }
                val endDay = if (index == bucketCount - 1) {
                    daysInMonth
                } else {
                    minOf(startDay + 6, daysInMonth)
                }
                val start = yearMonth.atDay(startDay).atStartOfDay(zoneId).toInstant()
                val end = yearMonth.atDay(endDay).plusDays(1).atStartOfDay(zoneId).toInstant()
                add(
                    TimelineBucket(
                        label = context.getString(com.theveloper.pixelplay.R.string.stats_week_label, index + 1),
                        startMillis = start.toEpochMilli(),
                        endMillis = end.toEpochMilli(),
                        inclusiveEnd = false
                    )
                )
            }
        }
    }

    private fun createYearBuckets(zoneId: ZoneId, now: Instant): List<TimelineBucket> {
        val year = Year.from(now.atZone(zoneId))
        return (1..12).map { monthIndex ->
            val start = year.atMonth(monthIndex).atDay(1).atStartOfDay(zoneId).toInstant()
            val end = year.atMonth(monthIndex).atEndOfMonth().plusDays(1).atStartOfDay(zoneId).toInstant()
            TimelineBucket(
                label = year.atMonth(monthIndex).month.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                startMillis = start.toEpochMilli(),
                endMillis = end.toEpochMilli(),
                inclusiveEnd = false
            )
        }
    }

    private fun createAllTimeBuckets(
        zoneId: ZoneId,
        spans: List<PlaybackSpan>,
        fallbackStart: Long,
        now: Instant
    ): List<TimelineBucket> {
        val allSpans = if (spans.isEmpty()) listOf(PlaybackSpan(fallbackStart, fallbackStart)) else spans
        val minTimestamp = allSpans.minOfOrNull { it.startMillis } ?: fallbackStart
        val maxTimestamp = allSpans.maxOfOrNull { it.endMillis } ?: now.toEpochMilli()
        val startYear = Instant.ofEpochMilli(minTimestamp).atZone(zoneId).year
        val endYear = Instant.ofEpochMilli(maxTimestamp).atZone(zoneId).year
        if (startYear > endYear) return emptyList()
        return (startYear..endYear).map { yearValue ->
            val year = Year.of(yearValue)
            val start = year.atDay(1).atStartOfDay(zoneId).toInstant()
            val end = year.plusYears(1).atDay(1).atStartOfDay(zoneId).toInstant()
            TimelineBucket(
                label = yearValue.toString(),
                startMillis = start.toEpochMilli(),
                endMillis = end.toEpochMilli(),
                inclusiveEnd = false
            )
        }
    }

    private fun StatsTimeRange.resolveBounds(
        events: List<PlaybackEvent>,
        nowMillis: Long,
        zoneId: ZoneId
    ): Pair<Long?, Long> {
        val nowInstant = Instant.ofEpochMilli(nowMillis)
        return when (this) {
            StatsTimeRange.DAY -> {
                val start = nowInstant.atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli()
                start to nowMillis
            }
            StatsTimeRange.WEEK -> {
                val start = nowInstant.atZone(zoneId)
                    .toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
                start to nowMillis
            }
            StatsTimeRange.MONTH -> {
                val start = YearMonth.from(nowInstant.atZone(zoneId))
                    .atDay(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
                start to nowMillis
            }
            StatsTimeRange.YEAR -> {
                val start = nowInstant.atZone(zoneId)
                    .toLocalDate()
                    .withDayOfYear(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
                start to nowMillis
            }
            StatsTimeRange.ALL -> {
                val start = events.minOfOrNull { it.startMillis() }
                start to nowMillis
            }
        }
    }

    private data class TimelineBucket(
        val label: String,
        val startMillis: Long,
        val endMillis: Long,
        val inclusiveEnd: Boolean
    )

    private fun PlaybackEvent.startMillis(): Long {
        val end = (endTimestamp ?: timestamp).coerceAtLeast(0L)
        val inferredStart = (startTimestamp ?: (end - durationMs)).coerceAtLeast(0L)
        return min(inferredStart, end)
    }

    private fun PlaybackEvent.endMillis(): Long {
        val end = (endTimestamp ?: timestamp).coerceAtLeast(0L)
        val start = startMillis()
        return max(end, start)
    }

    private fun notifyStatsChanged() {
        _refreshVersion.update { current ->
            if (current == Long.MAX_VALUE) {
                1L
            } else {
                current + 1L
            }
        }
    }

    companion object {
        private const val DEFAULT_PLAYBACK_HISTORY_LIMIT = 500
        private const val MAX_PLAYBACK_HISTORY_LIMIT = 5_000
        private const val MAX_FILE_UPDATE_RETRIES = 3
        private const val UNKNOWN_ARTIST = "Unknown Artist"
        private val MAX_HISTORY_AGE_MS = TimeUnit.DAYS.toMillis(730) // Keep roughly two years of history
        private const val SEGMENT_JOIN_TOLERANCE_MS = 0L
        private const val MAX_SONG_STATS_COUNT = 100
        private const val NAVIDROME_URI_PREFIX = "navidrome://"
        private const val LIVE_GATEWAY_ID_PREFIX = "navidrome_"
        private const val GATEWAY_SONG_ID_PREFIX = "yt-"
    }
}

enum class StatsTimeRange(val displayName: String) {
    DAY("Today"),
    WEEK("Week to Date"),
    MONTH("Month to Date"),
    YEAR("Year to Date"),
    ALL("All Time")
}
