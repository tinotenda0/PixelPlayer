package com.theveloper.pixelplay.data.jam

import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.theveloper.pixelplay.data.navidrome.ActiveSession
import com.theveloper.pixelplay.data.navidrome.DeviceSession
import com.theveloper.pixelplay.data.navidrome.JamCommand
import com.theveloper.pixelplay.data.navidrome.JamState
import com.theveloper.pixelplay.data.navidrome.NavidromeRepository
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.service.MusicService
import com.theveloper.pixelplay.data.service.PlaybackActivityTracker
import com.theveloper.pixelplay.utils.MediaItemBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.sse.EventSource
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cross-device playback: one canonical session per account, pushed live over SSE. Both "Jam"
 * (Spotify-Jam-style household control) and personal handoff (Spotify-Connect-style,
 * same account only) share one live connection and one session model — see backend
 * `handoff.py`.
 *
 * SESSION role (automatic): once this device has played something in this process, it publishes
 * its now-playing state whenever it changes locally, and applies any commands pushed to it over
 * its own live [EventSource] — by a household guest (Jam) or by another of this account's own
 * devices (personal handoff): play/pause/next/previous/seek/volume, `superseded` (a different
 * one of this account's own devices just took over — stop), and queue-loading (a command
 * carrying song ids replaces the local queue before the action is applied — this is how a
 * transfer/cast actually moves music, not just a position). Runs whether or not the UI is open,
 * via an app-scoped MediaController bound to [MusicService]. Registration does not require the
 * "allow household control" preference — that preference only controls whether this session is
 * *advertised* to Jam ([householdVisible]); a personal handoff between your own devices should
 * always work.
 *
 * GUEST role: [mySession] / [householdSessions] / [devices] stay live via the same connection,
 * no polling required — [controlHousehold] / [controlSelf] send a command to one, [transferTo] /
 * [pullFrom] move playback.
 */
@Singleton
class JamManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val navidromeRepository: NavidromeRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Stable per-process id: identifies this device, and lets a guest exclude itself. */
    val sessionId: String = UUID.randomUUID().hex()

    private val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private val _mySession = MutableStateFlow<ActiveSession?>(null)
    /** This account's one canonical active session, wherever it is — null if nothing is
     *  playing anywhere on the account right now. */
    val mySession: StateFlow<ActiveSession?> = _mySession.asStateFlow()

    private val _householdSessions = MutableStateFlow<List<ActiveSession>>(emptyList())
    /** Other household members' active, visible sessions — the auto-discovered Jam list. */
    val householdSessions: StateFlow<List<ActiveSession>> = _householdSessions.asStateFlow()

    private val _devices = MutableStateFlow<List<DeviceSession>>(emptyList())
    /** This account's other registered devices — the transfer pick-list. */
    val devices: StateFlow<List<DeviceSession>> = _devices.asStateFlow()

    @Volatile
    private var controller: MediaController? = null
    @Volatile
    private var eventSource: EventSource? = null
    @Volatile
    private var suppressNextPublish = false
    private var reconnectAttempt = 0

    /** Start the session role. Called once, app-scoped, from PixelPlayApplication. */
    fun start() {
        scope.launch {
            // Wait for this process's first real playback before registering at all - registering
            // eagerly on a cold, idle launch would bind (and thus start) MusicService just for
            // handoff visibility, which is a background-service lifetime cost nobody asked for.
            // Once that has happened, this device stays a valid handoff target - paused included -
            // until the process dies, which matches how Spotify Connect behaves.
            PlaybackActivityTracker.isPlaybackActiveFlow.first { it }
            navidromeRepository.registerDevice(deviceName, "android", sessionId, householdVisible())
            val c = ensureController() ?: return@launch
            attachPublishListener(c)
            connect()
            _mySession.value = navidromeRepository.getMySession()
            _householdSessions.value = navidromeRepository.getHouseholdSessions()
            _devices.value = navidromeRepository.getDevices(sessionId)
            publishNow()
            startPositionSync()
            startHygieneRefresh()
        }
    }

    private suspend fun householdVisible(): Boolean =
        userPreferencesRepository.allowHouseholdControlFlow.first()

    // ── Live push connection ────────────────────────────────────────────────
    private fun connect() {
        eventSource?.cancel()
        eventSource = navidromeRepository.subscribeSession(
            sessionId = sessionId,
            onSession = { session ->
                reconnectAttempt = 0
                if (session.user == navidromeRepository.username) {
                    _mySession.value = session
                } else {
                    _householdSessions.value =
                        _householdSessions.value.filter { it.user != session.user } + session
                }
            },
            onCommand = { cmd ->
                reconnectAttempt = 0
                scope.launch { applyCommand(cmd) }
            },
            onClosed = { scope.launch { reconnectWithBackoff() } },
        )
    }

    private suspend fun reconnectWithBackoff() {
        reconnectAttempt++
        val delayMs = (1000L shl (reconnectAttempt - 1).coerceIn(0, 5)).coerceAtMost(30_000L)
        delay(delayMs)
        connect()
    }

    /** Refreshes the device/household lists on a slow timer — the fast path is push, this just
     *  catches a device that vanished without a clean disconnect (crash, force-quit, dead
     *  network) rather than lingering forever in someone else's list. */
    private fun startHygieneRefresh() {
        scope.launch {
            while (true) {
                delay(HYGIENE_REFRESH_MS)
                runCatching {
                    _devices.value = navidromeRepository.getDevices(sessionId)
                    _householdSessions.value = navidromeRepository.getHouseholdSessions()
                }
            }
        }
    }

    private fun startPositionSync() {
        scope.launch {
            while (true) {
                delay(POSITION_SYNC_MS)
                val playing = withContext(Dispatchers.Main) { controller?.isPlaying == true }
                if (playing) publishNow()
            }
        }
    }

    // ── Personal handoff (same account) ─────────────────────────────────────
    suspend fun controlSelf(action: String, positionMs: Long? = null, volume: Float? = null): Boolean =
        navidromeRepository.sendCommand(
            action, positionMs, volume, targetUser = navidromeRepository.username
        )

    /** Push this device's current queue+position onto [targetId] (may be idle), then pause
     *  here. Once that device actually starts and publishes, the server's supersede push
     *  confirms the stop — pausing immediately just keeps the handoff feeling instant. */
    suspend fun transferTo(targetId: String): Boolean {
        val snapshot = readState() ?: return false
        val remaining = snapshot.queueIds.drop(snapshot.queueIndex)
        if (remaining.isEmpty()) return false
        val ok = navidromeRepository.sendCommand(
            "play", positionMs = snapshot.state.positionMs, songIds = remaining,
            targetSessionId = targetId
        )
        if (ok) withContext(Dispatchers.Main) { controller?.pause() }
        return ok
    }

    /** Pulls this account's active session — wherever it currently is — into this device and
     *  takes over. No target to pick: there's exactly one canonical session per account. */
    suspend fun pullFrom(): Boolean {
        val session = _mySession.value ?: return false
        val ids = session.state.queue
        if (ids.isEmpty()) return false
        val startIndex = session.state.queueIndex.coerceIn(0, ids.size - 1)
        val songs = navidromeRepository.getSongsByIds(ids.drop(startIndex))
        if (songs.isEmpty()) return false
        withContext(Dispatchers.Main) {
            val c = ensureController() ?: return@withContext
            c.setMediaItems(
                songs.map { MediaItemBuilder.build(it) }, 0,
                session.state.positionMs.coerceAtLeast(0)
            )
            c.prepare()
            if (session.state.isPlaying) c.play()
        }
        // Publishing (the track-change listener above) naturally supersedes whichever device
        // was active - no explicit "stop the old device" call needed here.
        return true
    }

    // ── Jam guest role (household) ──────────────────────────────────────────
    suspend fun controlHousehold(username: String, action: String, positionMs: Long? = null): Boolean =
        navidromeRepository.sendCommand(action, positionMs, targetUser = username)

    // ── MediaController plumbing (main-thread only) ─────────────────────────
    private suspend fun ensureController(): MediaController? {
        controller?.let { return it }
        return withContext(Dispatchers.Main) {
            runCatching {
                val token = SessionToken(context, ComponentName(context, MusicService::class.java))
                MediaController.Builder(context, token).buildAsync().await().also { controller = it }
            }.getOrNull()
        }
    }

    /** Publishes on every local track change and play/pause toggle. Skips exactly once when the
     *  isPlaying flip was a forced pause from being superseded (see applyCommand's "superseded"
     *  case) - otherwise that pause would immediately re-publish and steal the active slot right
     *  back from whichever device just took it. */
    private fun attachPublishListener(c: MediaController) {
        c.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                scope.launch { publishNow() }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (suppressNextPublish) {
                    suppressNextPublish = false
                    return
                }
                scope.launch { publishNow() }
            }
        })
    }

    private suspend fun publishNow() {
        val snapshot = readState() ?: return
        navidromeRepository.publishState(sessionId, snapshot.state, snapshot.queueIds, snapshot.queueIndex)
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
            "superseded" -> {
                // A different one of this account's own devices just became the active one.
                suppressNextPublish = true
                c.pause()
            }
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
        private const val POSITION_SYNC_MS = 5000L
        private const val HYGIENE_REFRESH_MS = 60_000L
    }
}
