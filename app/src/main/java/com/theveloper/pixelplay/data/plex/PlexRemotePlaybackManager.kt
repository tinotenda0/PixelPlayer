package com.theveloper.pixelplay.data.plex

import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.plex.model.PlexPlayerDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A Plex Companion session as the app's active output — the Plexamp
 * equivalent of "connecting to another player": song picks route to the
 * remote device, and its polled timeline drives the main player UI.
 */
@Singleton
class PlexRemotePlaybackManager @Inject constructor(
    private val repository: PlexRepository
) {
    data class Snapshot(
        val state: String,          // playing | paused | stopped | buffering
        val positionMs: Long,
        val durationMs: Long,
        val ratingKey: String?,
        val volume: Int?,
        val playQueueId: Long? = null
    )

    companion object {
        private const val TAG = "PlexRemotePlayback"
        private const val POLL_INTERVAL_MS = 1_500L
        // PlayQueue window pushed to the remote player around the tapped song.
        private const val QUEUE_ITEMS_BEFORE = 25
        private const val QUEUE_ITEMS_TOTAL = 100
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private val _activeDevice = MutableStateFlow<PlexPlayerDevice?>(null)
    val activeDevice: StateFlow<PlexPlayerDevice?> = _activeDevice.asStateFlow()

    private val _session = MutableStateFlow<Snapshot?>(null)
    val session: StateFlow<Snapshot?> = _session.asStateFlow()

    /** Queue last pushed to (or known for) the remote device, in play order. */
    @Volatile
    var remoteQueue: List<Song> = emptyList()
        private set

    // The device play queue whose songs are currently loaded into remoteQueue,
    // so we only re-fetch when the device actually switches queue.
    @Volatile
    private var loadedPlayQueueId: Long? = null

    val isActive: Boolean
        get() = _activeDevice.value != null

    /**
     * Start observing [device]. In join mode the caller does NOT push a queue,
     * so the phone just mirrors and remote-controls the device's existing
     * playback; in cast mode the caller follows with [playQueue].
     */
    fun connect(device: PlexPlayerDevice) {
        Timber.tag(TAG).i("Connecting to remote player ${device.name}")
        _activeDevice.value = device
        _session.value = null
        loadedPlayQueueId = null
        startPolling(device)
    }

    /** Stops controlling; the remote device keeps playing (Plexamp semantics). */
    fun disconnect() {
        Timber.tag(TAG).i("Disconnecting from remote player")
        pollJob?.cancel()
        pollJob = null
        _activeDevice.value = null
        _session.value = null
        remoteQueue = emptyList()
        loadedPlayQueueId = null
    }

    /**
     * Push a queue to the remote device and start at [startSong].
     * Only songs from the Plex library can play remotely; others are dropped.
     */
    suspend fun playQueue(
        songs: List<Song>,
        startSong: Song,
        startPositionMs: Long = 0L
    ): Boolean {
        val device = _activeDevice.value ?: return false
        val plexSongs = songs.filter { !it.plexId.isNullOrBlank() }
        val startPlexId = startSong.plexId
        if (plexSongs.isEmpty() || startPlexId.isNullOrBlank()) {
            Timber.tag(TAG).w("playQueue: no Plex-backed songs to send")
            return false
        }

        // Window the queue around the start song (play queues cap out server-side).
        val startIndex = plexSongs.indexOfFirst { it.plexId == startPlexId }.coerceAtLeast(0)
        val from = (startIndex - QUEUE_ITEMS_BEFORE).coerceAtLeast(0)
        val to = (from + QUEUE_ITEMS_TOTAL).coerceAtMost(plexSongs.size)
        val windowed = plexSongs.subList(from, to)

        val result = repository.playQueueOnDevice(
            device = device,
            ratingKeys = windowed.mapNotNull { it.plexId },
            startRatingKey = startPlexId,
            offsetMs = startPositionMs.coerceAtLeast(0L)
        )

        return result.fold(
            onSuccess = {
                remoteQueue = windowed
                // Optimistic snapshot so the UI flips immediately.
                _session.value = Snapshot(
                    state = "playing",
                    positionMs = startPositionMs,
                    durationMs = startSong.duration,
                    ratingKey = startPlexId,
                    volume = _session.value?.volume
                )
                true
            },
            onFailure = {
                Timber.tag(TAG).w(it, "playQueue failed")
                false
            }
        )
    }

    fun playPause() {
        val playing = _session.value?.state == "playing"
        sendCommand(if (playing) "pause" else "play")
        _session.value = _session.value?.copy(state = if (playing) "paused" else "playing")
    }

    fun play() {
        sendCommand("play")
        _session.value = _session.value?.copy(state = "playing")
    }

    fun pause() {
        sendCommand("pause")
        _session.value = _session.value?.copy(state = "paused")
    }

    fun next() = sendCommand("skipNext")

    fun previous() = sendCommand("skipPrevious")

    fun seekTo(positionMs: Long) {
        val device = _activeDevice.value ?: return
        _session.value = _session.value?.copy(positionMs = positionMs.coerceAtLeast(0L))
        scope.launch {
            repository.seekRemote(device, positionMs)
        }
    }

    fun setVolume(volume: Int) {
        val device = _activeDevice.value ?: return
        _session.value = _session.value?.copy(volume = volume.coerceIn(0, 100))
        scope.launch {
            repository.setRemoteVolume(device, volume)
        }
    }

    fun songForRatingKey(ratingKey: String?): Song? {
        if (ratingKey.isNullOrBlank()) return null
        return remoteQueue.firstOrNull { it.plexId == ratingKey }
    }

    suspend fun resolveSongForRatingKey(ratingKey: String?): Song? {
        if (ratingKey.isNullOrBlank()) return null
        return songForRatingKey(ratingKey) ?: repository.getSongByRatingKey(ratingKey)
    }

    private fun sendCommand(command: String) {
        val device = _activeDevice.value ?: return
        scope.launch {
            repository.sendRemoteCommand(device, command).onFailure {
                Timber.tag(TAG).w(it, "Remote command $command failed")
            }
        }
    }

    private fun startPolling(device: PlexPlayerDevice) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                repository.getRemoteTimeline(device).onSuccess { timeline ->
                    // A poll can complete right as disconnect() clears the
                    // session — never resurrect a snapshot for a dead session.
                    if (timeline != null && _activeDevice.value == device) {
                        _session.value = Snapshot(
                            state = timeline.state,
                            positionMs = timeline.timeMs,
                            durationMs = timeline.durationMs,
                            ratingKey = timeline.ratingKey,
                            volume = timeline.volume ?: _session.value?.volume,
                            playQueueId = timeline.playQueueId ?: _session.value?.playQueueId
                        )
                        // Keep remoteQueue in sync with whatever queue the device
                        // is actually playing — essential for join mode (we didn't
                        // push it) and keeps track resolution accurate in cast mode.
                        val pqId = timeline.playQueueId
                        if (pqId != null && pqId != loadedPlayQueueId) {
                            loadedPlayQueueId = pqId
                            val songs = repository.getPlayQueueSongs(pqId)
                            if (songs.isNotEmpty() && _activeDevice.value == device) {
                                remoteQueue = songs
                            }
                        }
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }
}
