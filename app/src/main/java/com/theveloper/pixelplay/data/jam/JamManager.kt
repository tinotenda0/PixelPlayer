package com.theveloper.pixelplay.data.jam

import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.theveloper.pixelplay.data.navidrome.DeviceSession
import com.theveloper.pixelplay.data.navidrome.JamCommand
import com.theveloper.pixelplay.data.navidrome.JamHost
import com.theveloper.pixelplay.data.navidrome.JamState
import com.theveloper.pixelplay.data.navidrome.NavidromeRepository
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.service.MusicService
import com.theveloper.pixelplay.data.service.PlaybackActivityTracker
import com.theveloper.pixelplay.utils.MediaItemBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cross-device playback: both "Jam" (Spotify-Jam-style household control) and personal handoff
 * (Plexamp/Spotify-Connect-style, same account only) share one session and one heartbeat loop,
 * because the gateway's session store is a single per-device record either kind of command can be
 * queued against — see backend `handoff.py`.
 *
 * SESSION role (automatic): once this device has played something in this process, it heartbeats
 * its now-playing state to the gateway and applies any commands queued for it — by a household
 * guest (Jam) or by another of this account's own devices (personal handoff): play/pause/next/
 * previous/seek/volume, and queue-loading (a command carrying song ids replaces the local queue
 * before the action is applied — this is how a transfer/cast actually moves music, not just a
 * position). Runs whether or not the UI is open, via an app-scoped MediaController bound to
 * [MusicService]. Registration does not require the "allow household control" preference — that
 * preference only controls whether this session is *advertised* to Jam ([householdVisible]); a
 * personal handoff between your own devices should always work.
 *
 * GUEST role (on demand): [startDiscovery] polls for household devices currently playing (Jam);
 * [startDeviceDiscovery] polls for this account's other devices, playing or not (personal).
 * [control] / [controlDevice] send a command to one; [transferTo] / [pullFrom] move playback.
 */
@Singleton
class JamManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val navidromeRepository: NavidromeRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Stable per-process id: identifies this device as a host, and lets the guest exclude itself. */
    val sessionId: String = UUID.randomUUID().hex()

    private val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private val _hosts = MutableStateFlow<List<JamHost>>(emptyList())
    /** Household devices currently playing (excludes this device). Updated while discovering. */
    val hosts: StateFlow<List<JamHost>> = _hosts.asStateFlow()

    private val _devices = MutableStateFlow<List<DeviceSession>>(emptyList())
    /** This account's other live devices, playing or idle. Updated while discovering. */
    val devices: StateFlow<List<DeviceSession>> = _devices.asStateFlow()

    @Volatile
    private var controller: MediaController? = null
    private var discoveryJob: Job? = null
    private var deviceDiscoveryJob: Job? = null

    /** Start the session role. Called once, app-scoped, from PixelPlayApplication. */
    fun start() {
        scope.launch {
            // Wait for this process's first real playback before registering at all - registering
            // eagerly on a cold, idle launch would bind (and thus start) MusicService just for
            // handoff visibility, which is a background-service lifetime cost nobody asked for.
            // Once that has happened, this device stays a valid handoff target - paused included -
            // until the process dies, which matches how Spotify Connect behaves.
            PlaybackActivityTracker.isPlaybackActiveFlow.first { it }
            navidromeRepository.registerJamDevice(
                deviceName, "android", sessionId, householdVisible()
            )
            while (true) {
                var playing = false
                runCatching {
                    val snapshot = readState()
                    val state = snapshot?.state ?: JamState()
                    playing = state.isPlaying
                    val commands = navidromeRepository.jamHeartbeat(
                        sessionId, state, snapshot?.queueIds.orEmpty(),
                        snapshot?.queueIndex ?: 0, householdVisible()
                    )
                    commands.forEach { applyCommand(it) }
                }
                delay(if (playing) HOST_HEARTBEAT_MS else HOST_IDLE_HEARTBEAT_MS)
            }
        }
    }

    private suspend fun householdVisible(): Boolean =
        userPreferencesRepository.allowHouseholdControlFlow.first()

    // ── Jam guest role (household) ─────────────────────────────────────────
    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = scope.launch {
            while (true) {
                _hosts.value = navidromeRepository.getJamHosts(sessionId).filter { it.id != sessionId }
                delay(GUEST_POLL_MS)
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    suspend fun control(hostId: String, action: String, positionMs: Long? = null): Boolean =
        navidromeRepository.jamControl(hostId, action, positionMs)

    // ── Personal handoff guest role (same account) ──────────────────────────
    fun startDeviceDiscovery() {
        if (deviceDiscoveryJob?.isActive == true) return
        deviceDiscoveryJob = scope.launch {
            while (true) {
                _devices.value = navidromeRepository.getDevices(sessionId)
                delay(GUEST_POLL_MS)
            }
        }
    }

    fun stopDeviceDiscovery() {
        deviceDiscoveryJob?.cancel()
        deviceDiscoveryJob = null
    }

    suspend fun controlDevice(
        targetId: String, action: String, positionMs: Long? = null,
        volume: Float? = null, songIds: List<String> = emptyList()
    ): Boolean = navidromeRepository.controlDevice(targetId, action, positionMs, volume, songIds)

    /** Push this device's current queue+position to [targetId], then pause playback here. */
    suspend fun transferTo(targetId: String): Boolean {
        val snapshot = readState() ?: return false
        val remaining = snapshot.queueIds.drop(snapshot.queueIndex)
        if (remaining.isEmpty()) return false
        val ok = navidromeRepository.controlDevice(
            targetId, "play", positionMs = snapshot.state.positionMs, songIds = remaining
        )
        if (ok) withContext(Dispatchers.Main) { controller?.pause() }
        return ok
    }

    /** Pull [targetId]'s current queue+position into this device, then pause it at the source. */
    suspend fun pullFrom(targetId: String): Boolean {
        val snapshot = navidromeRepository.getHandoffSnapshot(targetId) ?: return false
        val ids = snapshot.state.queue
        if (ids.isEmpty()) return false
        val startIndex = snapshot.state.queueIndex.coerceIn(0, ids.size - 1)
        val songs = navidromeRepository.getSongsByIds(ids.drop(startIndex))
        if (songs.isEmpty()) return false
        withContext(Dispatchers.Main) {
            val c = ensureController() ?: return@withContext
            c.setMediaItems(
                songs.map { MediaItemBuilder.build(it) }, 0,
                snapshot.state.positionMs.coerceAtLeast(0)
            )
            c.prepare()
            if (snapshot.state.isPlaying) c.play()
        }
        navidromeRepository.controlDevice(targetId, "pause")
        return true
    }

    // ── MediaController plumbing (main-thread only) ────────────────────────
    private suspend fun ensureController(): MediaController? {
        controller?.let { return it }
        return withContext(Dispatchers.Main) {
            runCatching {
                val token = SessionToken(context, ComponentName(context, MusicService::class.java))
                MediaController.Builder(context, token).buildAsync().await().also { controller = it }
            }.getOrNull()
        }
    }

    private data class LocalSnapshot(val state: JamState, val queueIds: List<String>, val queueIndex: Int)

    private suspend fun readState(): LocalSnapshot? = withContext(Dispatchers.Main) {
        val c = ensureController() ?: return@withContext null
        val item = c.currentMediaItem ?: return@withContext null
        val md = item.mediaMetadata
        val state = JamState(
            songId = item.wireId(),
            title = md.title?.toString().orEmpty(),
            artist = md.artist?.toString().orEmpty(),
            album = md.albumTitle?.toString().orEmpty(),
            coverArt = md.artworkUri?.toString().orEmpty(),
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.coerceAtLeast(0),
            isPlaying = c.isPlaying
        )
        val queueIds = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).wireId() }
        LocalSnapshot(state, queueIds, c.currentMediaItemIndex.coerceAtLeast(0))
    }

    /**
     * The id to report over the wire (Jam/handoff), as opposed to [MediaItem.mediaId] — which
     * for a gateway-sourced song is PixelPlayer's own locally-prefixed "navidrome_<id>" (see
     * [com.theveloper.pixelplay.data.model.Song.id]), meaningless to any other client. The raw
     * gateway id is already carried separately in the metadata extras for exactly this kind of
     * external use; fall back to mediaId for sources with no gateway id (nothing outside this
     * device could resolve those anyway).
     */
    private fun MediaItem.wireId(): String =
        mediaMetadata.extras?.getString(MediaItemBuilder.EXTERNAL_EXTRA_NAVIDROME_ID) ?: mediaId

    private suspend fun applyCommand(cmd: JamCommand) = withContext(Dispatchers.Main) {
        val c = ensureController() ?: return@withContext
        if (cmd.songIds.isNotEmpty()) {
            val songs = navidromeRepository.getSongsByIds(cmd.songIds)
            if (songs.isNotEmpty()) {
                c.setMediaItems(
                    songs.map { MediaItemBuilder.build(it) }, 0,
                    (cmd.positionMs ?: 0L).coerceAtLeast(0L)
                )
                c.prepare()
            }
        }
        when (cmd.action) {
            "playpause" -> if (c.isPlaying) c.pause() else c.play()
            "play" -> c.play()
            "pause" -> c.pause()
            "next" -> c.seekToNextMediaItem()
            "previous" -> c.seekToPreviousMediaItem()
            "seek" -> if (cmd.songIds.isEmpty()) cmd.positionMs?.let { c.seekTo(it) }
            "volume" -> cmd.volume?.let { c.volume = it.coerceIn(0f, 1f) }
            else -> Unit
        }
    }

    private suspend fun <T> ListenableFuture<T>.await(): T =
        suspendCancellableCoroutine { cont ->
            addListener({
                try {
                    cont.resume(get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }, { it.run() })
            cont.invokeOnCancellation { cancel(false) }
        }

    private fun UUID.hex(): String = toString().replace("-", "")

    companion object {
        // A command posts instantly regardless of these - they only bound how long THIS device
        // takes to notice one on its next poll. Matches music-pwa's mitigation (was 4000/15000;
        // the idle tier in particular was the dominant source of reported >10s control lag,
        // since a paused-but-open device sits on it, not the active one). A real fix is push
        // (WebSocket/SSE) instead of polling at all.
        private const val HOST_HEARTBEAT_MS = 2000L
        private const val HOST_IDLE_HEARTBEAT_MS = 5000L
        private const val GUEST_POLL_MS = 3000L
    }
}
