package com.theveloper.pixelplay.data.jam

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
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
import okhttp3.sse.EventSource
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
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
    @Volatile
    private var reconnectAttempt = 0

    /** Bumped by every [connect]. Terminal callbacks carry the generation they were opened
     *  with, so a close/failure from a subscription we have already replaced is ignored. That
     *  matters because `cancel()` on a live EventSource surfaces as `onFailure` — without this
     *  guard each reconnect would schedule a second one, and the reconnects would double on
     *  every round until the process was doing nothing but opening TLS connections. */
    private val connectionGeneration = AtomicInteger(0)
    private var reconnectJob: Job? = null
    /** `elapsedRealtime` when the live subscription opened, 0 while it is down. Used to tell a
     *  connection that survived from one that died instantly, so the backoff only restarts
     *  after a genuinely healthy stream drops. */
    @Volatile
    private var connectedAtMs = 0L

    /** Queue ids as last read off the controller. The queue only changes when the timeline
     *  does, so the periodic sync must not walk every media item (and unparcel every metadata
     *  Bundle) on the main thread just to republish the same list. */
    @Volatile
    private var cachedQueueIds: List<String>? = null

    private var positionSyncJob: Job? = null
    private var hygieneRefreshJob: Job? = null
    private var publishListenerAttached = false

    /** `elapsedRealtime` when [_mySession]'s current value arrived, so its position can be aged
     *  rather than re-fetched — see [JamPosition]. 0 while no sample is held. */
    @Volatile
    private var mySessionReceivedAtMs = 0L

    /** Set by whoever owns real playback (PlaybackDispatchStateHolder) — JamManager only knows
     *  the raw MediaController, not this app's own shuffle-reordering/repeat-mode logic, so it
     *  reaches out through these rather than reimplementing them. [shuffleRepeatProvider] feeds
     *  the current values into every publish; [onRemoteShuffle]/[onRemoteRepeat] apply an
     *  incoming remote command from another of this account's own devices. */
    var shuffleRepeatProvider: (() -> Pair<Boolean, String>)? = null
    var onRemoteShuffle: ((Boolean) -> Unit)? = null
    var onRemoteRepeat: ((String) -> Unit)? = null

    /** Start the session role. Called once, app-scoped, from PixelPlayApplication. */
    fun start() {
        scope.launch {
            // Wait for this process's first real playback before registering at all - registering
            // eagerly on a cold, idle launch would bind (and thus start) MusicService just for
            // handoff visibility, which is a background-service lifetime cost nobody asked for.
            // Once that has happened, this device stays a valid handoff target - paused included -
            // until the process dies, which matches how Spotify Connect behaves.
            PlaybackActivityTracker.isPlaybackActiveFlow.first { it }
            // Every call below is gateway-backed: signed out there is nothing to register with,
            // nothing to publish to and no stream to subscribe to, so the whole session role
            // stays parked instead of burning a 5 s timer and a reconnect loop on no-ops.
            navidromeRepository.isLoggedInFlow.collect { loggedIn ->
                if (loggedIn) startSessionRole() else stopSessionRole()
            }
        }
    }

    private suspend fun startSessionRole() {
        if (positionSyncJob?.isActive == true) return
        navidromeRepository.registerDevice(deviceName, "android", sessionId, householdVisible())
        val c = ensureController() ?: return
        attachPublishListener(c)
        connect()
        setMySession(navidromeRepository.getMySession())
        _householdSessions.value = navidromeRepository.getHouseholdSessions()
        _devices.value = navidromeRepository.getDevices(sessionId)
        publishNow()
        startPositionSync()
        startHygieneRefresh()
    }

    @Synchronized
    private fun stopSessionRole() {
        reconnectJob?.cancel()
        reconnectJob = null
        positionSyncJob?.cancel()
        positionSyncJob = null
        hygieneRefreshJob?.cancel()
        hygieneRefreshJob = null
        connectionGeneration.incrementAndGet()
        eventSource?.cancel()
        eventSource = null
        connectedAtMs = 0L
        reconnectAttempt = 0
        setMySession(null)
        _householdSessions.value = emptyList()
        _devices.value = emptyList()
    }

    /** Every path that stores a session also stamps when it arrived, so [remotePositionMs]
     *  can age it. */
    private fun setMySession(session: ActiveSession?) {
        mySessionReceivedAtMs = if (session == null) 0L else SystemClock.elapsedRealtime()
        _mySession.value = session
    }

    /** [session]'s playhead as of now, aged from when this device received it. Use this
     *  anywhere a remote position is acted on — resuming a pull, drawing a progress bar —
     *  instead of the raw [PlayerSessionState.positionMs], which is only true as of its
     *  sample instant. */
    fun remotePositionMs(session: ActiveSession): Long = JamPosition.extrapolate(
        sampledPositionMs = session.state.positionMs,
        isPlaying = session.state.isPlaying,
        receivedAtElapsedMs = if (session === _mySession.value) mySessionReceivedAtMs else 0L,
        nowElapsedMs = SystemClock.elapsedRealtime(),
        durationMs = session.state.durationMs,
    )

    private suspend fun householdVisible(): Boolean =
        userPreferencesRepository.allowHouseholdControlFlow.first()

    // ── Live push connection ────────────────────────────────────────────────
    @Synchronized
    private fun connect() {
        if (!navidromeRepository.isLoggedIn) return
        // Take the new generation *before* cancelling: the cancellation is delivered as a
        // failure on the old source, which must then be recognised as stale and dropped.
        val generation = connectionGeneration.incrementAndGet()
        connectedAtMs = 0L
        eventSource?.cancel()
        eventSource = navidromeRepository.subscribeSession(
            sessionId = sessionId,
            onOpen = {
                if (generation == connectionGeneration.get()) {
                    connectedAtMs = SystemClock.elapsedRealtime()
                }
            },
            onSession = { session ->
                if (session.user == navidromeRepository.username) {
                    setMySession(session)
                } else {
                    _householdSessions.value =
                        _householdSessions.value.filter { it.user != session.user } + session
                }
            },
            onCommand = { cmd ->
                scope.launch { applyCommand(cmd) }
            },
            onClosed = { scheduleReconnect(generation) },
            onDevice = { device ->
                // Instant pick-up for an already-open Devices screen, instead of waiting for
                // the next hygiene tick — same merge-by-id the hygiene refresh does.
                _devices.value = _devices.value.filter { it.id != device.id } + device
            },
        )
    }

    /** One drop schedules exactly one reconnect: callbacks from a superseded subscription are
     *  ignored, and a pending reconnect is replaced rather than stacked. */
    @Synchronized
    private fun scheduleReconnect(generation: Int) {
        if (generation != connectionGeneration.get()) return
        val openedAtMs = connectedAtMs
        connectedAtMs = 0L
        // A stream that stayed up is a healthy one that merely dropped — reconnect promptly.
        // One that died on arrival keeps climbing the backoff instead of hammering the server.
        if (openedAtMs != 0L &&
            SystemClock.elapsedRealtime() - openedAtMs >= STABLE_CONNECTION_MS
        ) {
            reconnectAttempt = 0
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch { reconnectWithBackoff() }
    }

    private suspend fun reconnectWithBackoff() {
        reconnectAttempt++
        val delayMs = (1000L shl (reconnectAttempt - 1).coerceIn(0, 5)).coerceAtMost(30_000L)
        delay(delayMs)
        connect()
    }

    /** Pulls the device/household lists immediately — for the Devices screen to call as soon
     *  as it opens, rather than showing whatever was last known (possibly up to
     *  [HYGIENE_REFRESH_MS] stale) until the next background tick. */
    suspend fun refreshDevices() {
        runCatching {
            _devices.value = navidromeRepository.getDevices(sessionId)
            _householdSessions.value = navidromeRepository.getHouseholdSessions()
        }
    }

    /** Refreshes the device/household lists on a slow timer — the fast path is push, this just
     *  catches a device that vanished without a clean disconnect (crash, force-quit, dead
     *  network) rather than lingering forever in someone else's list. */
    private fun startHygieneRefresh() {
        hygieneRefreshJob?.cancel()
        hygieneRefreshJob = scope.launch {
            while (true) {
                delay(HYGIENE_REFRESH_MS)
                refreshDevices()
            }
        }
    }

    private fun startPositionSync() {
        positionSyncJob?.cancel()
        positionSyncJob = scope.launch {
            while (true) {
                delay(POSITION_SYNC_MS)
                val playing = withContext(Dispatchers.Main) { controller?.isPlaying == true }
                if (playing) publishNow()
            }
        }
    }

    // ── Personal handoff (same account) ─────────────────────────────────────
    suspend fun controlSelf(
        action: String, positionMs: Long? = null, volume: Float? = null,
        shuffle: Boolean? = null, repeat: String? = null,
    ): Boolean =
        navidromeRepository.sendCommand(
            action, positionMs, volume, targetUser = navidromeRepository.username,
            shuffle = shuffle, repeat = repeat,
        )

    /** Sends a freshly-selected queue to whichever device is currently this account's active
     *  one — used when a song/playlist/artist is picked here while a DIFFERENT device already
     *  holds the active slot, so the selection controls that device instead of silently
     *  starting a competing local session here (Spotify-Connect style). No target id needed:
     *  targetUser always resolves server-side to "whichever device is active for this user". */
    suspend fun sendQueueToActiveSession(songIds: List<String>): Boolean {
        if (songIds.isEmpty()) return false
        return navidromeRepository.sendCommand(
            "play", songIds = songIds, targetUser = navidromeRepository.username
        )
    }

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
        // Age the sample: with a slow publish cadence the raw positionMs can be tens of
        // seconds behind, and resuming there would replay audio the user already heard.
        val resumePositionMs = remotePositionMs(session)
        val songs = navidromeRepository.getSongsByIds(ids.drop(startIndex))
        if (songs.isEmpty()) return false
        withContext(Dispatchers.Main) {
            val c = ensureController() ?: return@withContext
            c.setMediaItems(
                songs.map { MediaItemBuilder.build(it) }, 0,
                resumePositionMs
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
        if (publishListenerAttached) return
        publishListenerAttached = true
        c.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                cachedQueueIds = null
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                scope.launch { publishNow() }
            }

            // A seek jumps the playhead, which is exactly what a receiver ageing our last
            // sample cannot predict. Publish at once rather than leaving peers extrapolating
            // from a position that stopped being true until the next tick comes round.
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    scope.launch { publishNow() }
                }
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
        val (shuffleNow, repeatNow) = shuffleRepeatProvider?.invoke() ?: (false to "off")
        val state = JamState(
            songId = item.wireId(),
            title = md.title?.toString().orEmpty(),
            artist = md.artist?.toString().orEmpty(),
            album = md.albumTitle?.toString().orEmpty(),
            coverArt = md.artworkUri?.toString().orEmpty(),
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.coerceAtLeast(0),
            isPlaying = c.isPlaying,
            shuffle = shuffleNow,
            repeat = repeatNow
        )
        // Rebuilt only when the timeline actually changed — see [cachedQueueIds].
        val queueIds = cachedQueueIds
            ?: (0 until c.mediaItemCount).map { c.getMediaItemAt(it).wireId() }
                .also { cachedQueueIds = it }
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
            "shuffle" -> cmd.shuffle?.let { onRemoteShuffle?.invoke(it) }
            "repeat" -> cmd.repeat?.let { onRemoteRepeat?.invoke(it) }
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
        // Position no longer rides on this timer: peers age the last sample themselves
        // ([JamPosition]), and every playhead jump - track change, play/pause, seek -
        // publishes immediately. What is left is drift correction and session liveness,
        // which do not need five-second resolution. Each tick is a fresh authenticated HTTP
        // POST carrying the whole queue, so this interval is a direct battery/radio cost.
        private const val POSITION_SYNC_MS = 30_000L
        private const val HYGIENE_REFRESH_MS = 60_000L
        /** How long a subscription must stay open to count as healthy for backoff. */
        private const val STABLE_CONNECTION_MS = 30_000L
    }
}
