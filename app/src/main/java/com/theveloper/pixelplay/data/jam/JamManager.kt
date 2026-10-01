package com.theveloper.pixelplay.data.jam

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.navidrome.ActiveSession
import com.theveloper.pixelplay.data.navidrome.DeviceSession
import com.theveloper.pixelplay.data.navidrome.JamCommand
import com.theveloper.pixelplay.data.navidrome.JamState
import com.theveloper.pixelplay.data.navidrome.NavidromeRepository
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.service.MusicService
import com.theveloper.pixelplay.data.service.PlaybackActivityTracker
import com.theveloper.pixelplay.data.service.player.LocalPlayback
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.sse.EventSource
import timber.log.Timber
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
    private val localPlayback: LocalPlayback,
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

    /** Whether the guest role is up, so a re-login does not register and subscribe twice. */
    @Volatile
    private var guestRoleStarted = false
    private var hygieneRefreshJob: Job? = null
    private var publishListenerAttached = false

    /** Process-level visibility, from [ProcessLifecycleOwner]. Feeds [JamLiveMode]. */
    private val appInForeground = MutableStateFlow(false)
    /** True while [suspendLive] has the connection deliberately down. */
    @Volatile
    private var liveSuspended = false
    /** Pending [suspendLive] for an idle, backgrounded device; cancelled if it wakes first. */
    private var idleDisconnectJob: Job? = null
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
        // Guest role: know what this account is playing, from launch onwards.
        //
        // This deliberately does NOT wait for local playback. Waiting is what made an idle
        // device show its own stale track forever: never having played anything this process,
        // it never subscribed, so it never learned a session existed. Mirroring the active
        // device is precisely the case where this one has played nothing.
        //
        // Gateway-backed either way, so signed out there is nothing to subscribe to and the
        // whole thing stays parked rather than burning a reconnect loop on no-ops.
        //
        // What *does* gate it is whether anyone benefits: see [JamLiveMode]. Called from
        // Application.onCreate, so this is on the main thread, as addObserver requires.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { appInForeground.value = true }
            override fun onStop(owner: LifecycleOwner) { appInForeground.value = false }
        })
        scope.launch {
            combine(
                navidromeRepository.isLoggedInFlow,
                appInForeground,
                PlaybackActivityTracker.isPlaybackActiveFlow,
            ) { loggedIn, foreground, playing -> JamLiveMode.of(loggedIn, foreground, playing) }
                .distinctUntilChanged()
                .collect { mode -> applyLiveMode(mode) }
        }

        // Host role: publishing our own playback, which only means anything once there is some.
        //
        // The wait survives from the original design and still earns its place, though for a
        // narrower reason than before: it is [ensureController] that binds — and so starts —
        // MusicService, and paying for a background service on a cold, idle launch is a cost
        // nobody asked for. Registering and subscribing, above, do not bind anything.
        scope.launch {
            PlaybackActivityTracker.isPlaybackActiveFlow.first { it }
            navidromeRepository.isLoggedInFlow.collect { loggedIn ->
                if (loggedIn) startHostRole()
            }
        }
    }

    private suspend fun applyLiveMode(mode: JamLiveMode?) {
        idleDisconnectJob?.cancel()
        idleDisconnectJob = null
        when (mode) {
            null -> stopSessionRole()
            JamLiveMode.FOREGROUND -> {
                startGuestRole()
                resumeLive()
                startHygieneRefresh()
            }
            JamLiveMode.BACKGROUND_PLAYING -> {
                startGuestRole()
                resumeLive()
                stopHygieneRefresh()
            }
            JamLiveMode.IDLE -> {
                stopHygieneRefresh()
                // Never started (launched straight into the background, e.g. by a widget or a
                // worker): nothing to tear down, and nothing worth registering for.
                if (!guestRoleStarted) return
                idleDisconnectJob = scope.launch {
                    delay(JamLiveMode.IDLE_DISCONNECT_GRACE_MS)
                    suspendLive()
                }
            }
        }
    }

    /** Identity and a live view of the account's session. Binds nothing. */
    private suspend fun startGuestRole() {
        if (guestRoleStarted) return
        guestRoleStarted = true
        navidromeRepository.registerDevice(deviceName, "android", sessionId, householdVisible())
        connect()
        setMySession(navidromeRepository.getMySession())
        _householdSessions.value = navidromeRepository.getHouseholdSessions()
        _devices.value = navidromeRepository.getDevices(sessionId)
    }

    /** Drops the live connection without forgetting the session role, for [JamLiveMode.IDLE].
     *  The server stops listing this device once it has been disconnected past its registry
     *  TTL, which is the honest state for a device nobody can currently reach. */
    @Synchronized
    private fun suspendLive() {
        if (liveSuspended) return
        liveSuspended = true
        reconnectJob?.cancel()
        reconnectJob = null
        // New generation first, so the cancellation's onFailure is recognised as stale and
        // does not schedule a reconnect (see [connectionGeneration]).
        connectionGeneration.incrementAndGet()
        eventSource?.cancel()
        eventSource = null
        connectedAtMs = 0L
        reconnectAttempt = 0
        Timber.tag("JamManager").d("Idle in background: live connection suspended")
    }

    /** Reopens a connection [suspendLive] closed, and catches up on whatever changed meanwhile
     *  — nothing was pushed to this device while it was away. */
    private suspend fun resumeLive() {
        synchronized(this) {
            if (!liveSuspended) return
            liveSuspended = false
        }
        // Re-register too: past the server's registry TTL this device was forgotten entirely.
        navidromeRepository.registerDevice(deviceName, "android", sessionId, householdVisible())
        connect()
        setMySession(navidromeRepository.getMySession())
        refreshDevices()
    }

    /** Publishing this device's own playback. Needs the controller, and so MusicService. */
    private suspend fun startHostRole() {
        if (positionSyncJob?.isActive == true) return
        // Starts MusicService, which is what makes an engine player exist to listen to.
        ensureController() ?: return
        attachPublishListener(localPlayback.player() ?: return)
        publishNow()
        startPositionSync()
    }

    @Synchronized
    private fun stopSessionRole() {
        guestRoleStarted = false
        liveSuspended = false
        idleDisconnectJob?.cancel()
        idleDisconnectJob = null
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
        // A reconnect that was already scheduled when the device went idle must not undo it.
        if (liveSuspended) return
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
        delay(JamBackoff.delayMs(reconnectAttempt))
        // Re-register before resubscribing. The usual reason a healthy stream drops is a backend
        // restart (every deploy), which empties the server's in-memory device registry; an
        // unregistered device's publishes are pruned straight back out of the active session,
        // so without this, handoff silently stopped working until the app itself restarted.
        // Idempotent and cheap when the registration did survive.
        if (!liveSuspended) {
            navidromeRepository.registerDevice(deviceName, "android", sessionId, householdVisible())
        }
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
        if (hygieneRefreshJob?.isActive == true) return
        hygieneRefreshJob = scope.launch {
            while (true) {
                delay(HYGIENE_REFRESH_MS)
                refreshDevices()
            }
        }
    }

    /** The lists it refreshes are only ever shown in the UI, so the timer only runs while the
     *  app is visible; coming back to the foreground restarts it. */
    private fun stopHygieneRefresh() {
        hygieneRefreshJob?.cancel()
        hygieneRefreshJob = null
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
    suspend fun sendQueueToActiveSession(songIds: List<String>, index: Int = 0): Boolean {
        if (songIds.isEmpty()) return false
        return navidromeRepository.sendCommand(
            "play", songIds = songIds, index = index,
            targetUser = navidromeRepository.username
        )
    }

    /** Push this device's current queue+position onto [targetId] (may be idle), then pause
     *  here. Once that device actually starts and publishes, the server's supersede push
     *  confirms the stop — pausing immediately just keeps the handoff feeling instant. */
    suspend fun transferTo(targetId: String): Boolean {
        val snapshot = readState() ?: return false
        if (snapshot.queueIds.isEmpty()) return false
        // The whole queue plus where we are in it, not just the part from here on: the target
        // should be able to skip back into what already played, the way a Connect handoff does.
        val ok = navidromeRepository.sendCommand(
            "play", positionMs = snapshot.state.positionMs, songIds = snapshot.queueIds,
            index = snapshot.queueIndex, targetSessionId = targetId
        )
        // Pausing here, on the engine's player. Transferring away makes the target the active
        // one, so a pause sent through the session would follow it there and stop the music we
        // just handed over instead of the music in this room.
        if (ok) withContext(Dispatchers.Main) { localPlayback.player()?.pause() }
        return ok
    }

    /** Pulls this account's active session — wherever it currently is — into this device and
     *  takes over. No target to pick: there's exactly one canonical session per account. */
    suspend fun pullFrom(): Boolean {
        val session = _mySession.value
        if (session == null) {
            Timber.w("$TAG: pullFrom: nothing to pull, no active session known here")
            return false
        }
        // A publisher that sends no queue still tells us what it is playing, and pulling one
        // track is a far better answer than doing nothing - which is how this looked when the
        // session came from a client that does not publish its queue.
        val ids = session.state.queue.ifEmpty {
            listOfNotNull(session.state.songId.takeIf { it.isNotBlank() })
        }
        if (ids.isEmpty()) {
            Timber.w("$TAG: pullFrom: session has neither a queue nor a current song")
            return false
        }
        // Age the sample: with a slow publish cadence the raw positionMs can be tens of
        // seconds behind, and resuming there would replay audio the user already heard.
        val resumePositionMs = remotePositionMs(session)
        // Take the whole queue, not just the rest of it, so pulling playback here keeps the
        // history the other device had.
        val resolved = resolveQueue(ids, session.state.queueIndex)
        if (resolved == null) {
            // Every id the other device published was unresolvable here. Worth saying out loud:
            // it is the signature of an id-shape mismatch, which has bitten this path before.
            Timber.w(
                "$TAG: pullFrom: none of ${'$'}{ids.size} ids resolved, first=${'$'}{ids.firstOrNull()}"
            )
            return false
        }
        withContext(Dispatchers.Main) {
            val started = ensureController() ?: return@withContext
            val c = localPlayback.player() ?: started
            c.setMediaItems(
                resolved.songs.map { MediaItemBuilder.build(it) }, resolved.startIndex,
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
    /**
     * Watches *local* playback so this device can publish what it is playing.
     *
     * Attached to the engine's player, never to a MediaController. A controller observes the
     * media session, which presents whichever device owns playback - so while mirroring, every
     * update from the remote device would fire this and make us publish, claiming a session we
     * do not own. The other device then mirrors that and claims it back: the bouncing.
     *
     * Reading local state was not enough on its own; the trigger has to be local too.
     */
    private fun attachPublishListener(player: Player) {
        if (publishListenerAttached) return
        publishListenerAttached = true
        player.addListener(object : Player.Listener {
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

            // Publish the new level straight away so a remote's slider settles where the user
            // left it. Waiting for the next position sync would leave it showing the old
            // value for up to POSITION_SYNC_MS, which reads as the control having failed.
            override fun onVolumeChanged(volume: Float) {
                scope.launch { publishNow() }
            }
        })
    }

    /** Orders this device's publishes against each other. They are independent HTTP requests,
     *  so a delayed one can land after a newer one and overwrite state that stopped being
     *  true; the server drops anything carrying a lower count than it has already seen. */
    private val publishSeq = AtomicInteger(0)

    private suspend fun publishNow() {
        val snapshot = readState() ?: return
        navidromeRepository.publishState(
            sessionId, snapshot.state, snapshot.queueIds, snapshot.queueIndex,
            seq = publishSeq.incrementAndGet()
        )
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
            // From the engine, not the controller: publishes are triggered by the engine
            // player's own onIsPlayingChanged, and the controller only hears about that change
            // over IPC a moment later. Reading it here reported "paused" for a track that had
            // just started, which left peers showing it paused until the next position sync
            // and stopped the gateway prefetching the tracks after it.
            isPlaying = localPlayback.player()?.isPlaying ?: c.isPlaying,
            shuffle = shuffleNow,
            repeat = repeatNow,
            volume = c.volume,
            // Local playback through the MediaController can always be attenuated, so this is
            // constant here. It exists for outputs where that is not true - a fixed-level
            // endpoint should advertise false rather than accept volume commands silently.
            supportsVolume = true,
            // Media3 already tracks what is possible for the current item and queue, so this
            // is a read rather than bookkeeping of our own.
            disallows = JamDisallows.derive(
                canSkipPrev = c.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM),
                canSkipNext = c.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM),
                canSeek = c.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
                canToggleShuffle = c.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE),
                canToggleRepeat = c.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE),
            )
        )
        // Take the queue from the engine, not from this controller.
        //
        // DualPlayerEngine loads only a window of a large queue into ExoPlayer, so the
        // controller's mediaItemCount is that window and currentMediaItemIndex is measured
        // against it - which is why the engine keeps getFullQueue and getCurrentAbsoluteIndex
        // at all. Publishing the controller's view hands another device part of the queue and
        // an index that means nothing outside this process, and once a receiver adopts that
        // it republishes it, so a single bad hop follows the session around.
        val engineQueue = localPlayback.queue()
        if (engineQueue != null) {
            return@withContext LocalSnapshot(
                state,
                engineQueue.items.map { it.wireId() },
                engineQueue.absoluteIndex.coerceIn(0, engineQueue.items.lastIndex),
            )
        }

        // No engine view (nothing playing locally yet): the controller is all there is.
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
        mediaMetadata.extras?.getString(MediaItemBuilder.EXTERNAL_EXTRA_NAVIDROME_ID)
            // The extra goes missing whenever a Song reaches the player without its gateway id
            // - a restored queue, a download - and the fallback then published `mediaId`, which
            // is the *locally* prefixed form. The gateway has never heard of that prefix, so
            // every id in the queue failed to resolve and handoff quietly did nothing: transfer
            // played the wrong track, pulling played none at all. The prefix is our own
            // convention, so strip it back off rather than publishing an id nobody can use.
            ?: mediaId.removePrefix(LOCAL_ID_PREFIX)

    /**
     * Applies a command pushed to this device.
     *
     * Runs with routing suppressed throughout. This device drives playback through a
     * MediaController bound to the same session `RoutingPlayer` sits behind, so if a route were
     * still considered active here, every call below would be forwarded straight back out to
     * that route rather than played on this device — a loop, and a transfer that never lands.
     *
     * That state is reachable in normal use: a command means this device is becoming the active
     * one, but the command and the session update saying so arrive over the wire independently,
     * so the `play` can land first. Suppressing for the whole block covers that window instead
     * of relying on the two racing in a particular order.
     */
    /**
     * Applies a command pushed to this device.
     *
     * Everything below acts on the engine's player, never on a MediaController. A controller
     * would reach this device's media session, which presents whichever device owns playback -
     * so while another one still holds the session, a command meant for us would be forwarded
     * straight back out to it. Stopping because we were superseded is precisely that case.
     */
    private suspend fun applyCommand(cmd: JamCommand) = withContext(Dispatchers.Main) {
        applyCommandLocally(cmd)
    }

    private suspend fun applyCommandLocally(cmd: JamCommand) {
        // Binding the controller is what starts MusicService, which an idle device being handed
        // a queue needs. Having started it, act on the engine's player instead: the session in
        // between presents whoever owns playback, which right now may still be the sender.
        val started = ensureController() ?: return
        val c = localPlayback.player() ?: started
        if (cmd.songIds.isNotEmpty()) {
            // The queue arrives whole, with [index] marking where to start, so whatever played
            // before that point stays behind the playhead and back-skip works.
            val resolved = resolveQueue(cmd.songIds, cmd.index)
            if (resolved != null) {
                c.setMediaItems(
                    resolved.songs.map { MediaItemBuilder.build(it) }, resolved.startIndex,
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

    private class ResolvedQueue(val songs: List<Song>, val startIndex: Int)

    /**
     * Turns a wire queue (ids + the index to start on) into songs plus the index of that same
     * track in the resolved list.
     *
     * [NavidromeRepository.getSongsByIds] keeps caller order but silently drops ids the server
     * cannot resolve, so the wire index does not survive the round trip: one dropped id before
     * the start point and the queue would begin on the wrong song. Re-finding the intended
     * track by id is the only alignment that holds. Null when nothing resolved at all.
     */
    private suspend fun resolveQueue(songIds: List<String>, index: Int): ResolvedQueue? {
        if (songIds.isEmpty()) return null
        val songs = navidromeRepository.getSongsByIds(songIds)
        if (songs.isEmpty()) return null
        val startIndex = JamQueue.alignStartIndex(songIds, index, songs.map { it.navidromeId })
        // Landing on the wrong track is the failure this path keeps producing, and from the
        // outside it is indistinguishable from a stale session. Say which it was: how much of
        // the queue survived resolution, the index asked for, and the index actually used.
        Timber.i(
            "%s: resolveQueue sent=%d resolved=%d askedIndex=%d startIndex=%d wanted=%s got=%s",
            TAG, songIds.size, songs.size, index, startIndex,
            songIds.getOrNull(index), songs.getOrNull(startIndex)?.navidromeId,
        )
        return ResolvedQueue(songs, startIndex)
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
        private const val TAG = "JamManager"

        /** How NavidromeRepository prefixes a gateway id locally; never sent over the wire. */
        private const val LOCAL_ID_PREFIX = "navidrome_"

        // Position no longer rides on this timer: peers age the last sample themselves
        // ([JamPosition]), and every playhead jump - track change, play/pause, seek -
        // publishes immediately. What is left is drift correction and session liveness,
        // which do not need five-second resolution. Each tick is a fresh authenticated HTTP
        // POST carrying the whole queue, so this interval is a direct battery/radio cost.
        //
        // This doubles as the server's liveness signal, so it is half of a contract: the
        // backend's `CONNECTION_STALE_SECONDS` (handoff.py) is three times this value and must
        // move with it. Leave them out of step and a healthy device reads as stale, which
        // double-delivers commands and replays them as duplicate skips after a reconnect.
        private const val POSITION_SYNC_MS = 30_000L
        private const val HYGIENE_REFRESH_MS = 60_000L
        /** How long a subscription must stay open to count as healthy for backoff. */
        private const val STABLE_CONNECTION_MS = 30_000L
    }
}
