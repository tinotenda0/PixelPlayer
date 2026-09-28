package com.theveloper.pixelplay.data.jam

import android.os.SystemClock
import com.theveloper.pixelplay.data.service.player.PlaybackRoute
import com.theveloper.pixelplay.data.service.player.PlaybackRouteRegistry
import com.theveloper.pixelplay.data.service.player.RouteState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Another of this account's own devices, presented as somewhere playback can be happening.
 *
 * This is what makes an idle device stop showing its own stale track. The account has exactly
 * one canonical session ([JamManager.mySession]); whenever that session belongs to a *different*
 * device, this route owns playback here, and
 * [com.theveloper.pixelplay.data.service.player.RoutingPlayer] presents it as the player behind
 * the media session. Every surface reading that session then shows the remote device and
 * controls it — the mini and full player, the notification, the lock screen, widgets and Wear —
 * without any of them knowing a route exists.
 *
 * Commands go back out as ordinary handoff commands, which the gateway delivers to whichever
 * device currently holds the session. Nothing is applied optimistically: the remote device is
 * the authority on whether a skip happened, and its next published state is what moves the UI.
 */
@Singleton
class HandoffRoute @Inject constructor(
    private val jamManager: JamManager,
    private val registry: PlaybackRouteRegistry,
) : PlaybackRoute {

    override val name: String
        get() = jamManager.mySession.value?.deviceName ?: "Another device"

    /**
     * Set while this device claims playback, until the gateway confirms it. Stops an in-flight
     * session update - which still names the previous device - from re-presenting it here.
     */
    @Volatile
    private var takingOver = false

    private val _isActive = MutableStateFlow(false)
    override val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _state = MutableStateFlow(RouteState())
    override val state: StateFlow<RouteState> = _state.asStateFlow()

    /** App-scoped, set by [start]; commands are sent on it. */
    private lateinit var scope: CoroutineScope

    /**
     * Starts following the account's session and claiming the route slot while it belongs to
     * somewhere else. Called once, app-scoped.
     */
    fun start(scope: CoroutineScope) {
        this.scope = scope
        scope.launch {
            jamManager.mySession.collect { session ->
                // Ours, or nobody's, means playback is local and there is nothing to present.
                val remote = session
                    ?.takeIf { it.activeDeviceId.isNotBlank() }
                    ?.takeIf { it.activeDeviceId != jamManager.sessionId }

                if (remote == null) {
                    // The session naming us is the confirmation a takeover was waiting for.
                    takingOver = false
                    _isActive.value = false
                    registry.unregister(this@HandoffRoute)
                    return@collect
                }

                // Mid-takeover the session still names the old device for a moment, because the
                // update saying we now own playback has not been published yet. Re-presenting
                // that device here would send the music we are trying to claim straight back to
                // it, which is exactly how pulling used to fail.
                if (takingOver) return@collect

                _state.value = remote.toRouteState()
                _isActive.value = true
                registry.register(this@HandoffRoute)
            }
        }
    }

    /**
     * Gives playback back to this device, now.
     *
     * Pulling, or being handed a queue, means this device is becoming the active one. Both drive
     * the local player through a MediaController bound to the session [RoutingPlayer] sits
     * behind, so the route has to be gone *before* those calls land or they are forwarded to the
     * very device we are taking over from.
     *
     * Suppressing around the call site cannot do this: MediaController calls are asynchronous
     * IPC, so the session handles them well after any flag set around `play()` has been
     * restored. Standing the route down and keeping it down until the gateway confirms us as
     * active is the only version of this that holds.
     */
    fun takeOver() {
        takingOver = true
        _isActive.value = false
        registry.unregister(this)
    }

    /**
     * Turns a wire song id into the id this app knows the track by.
     *
     * The gateway speaks raw ids (`yt-xxxx`); locally a gateway-sourced song is
     * `navidrome_yt-xxxx` (see `toSong()` in NavidromeRepository). The player surface resolves
     * whatever the media session reports back to a local Song to decide what to draw, so a raw
     * id resolves to nothing and the mini player never appears - the session reads correctly
     * while the app looks empty, which is exactly how this failed.
     *
     * Tolerates an already-prefixed id, since a device on an older build publishes that shape.
     */
    private fun String.asLocalSongId(): String =
        if (startsWith(LOCAL_ID_PREFIX)) this else LOCAL_ID_PREFIX + this

    private fun com.theveloper.pixelplay.data.navidrome.ActiveSession.toRouteState(): RouteState {
        val s = state
        return RouteState(
            mediaId = s.songId.takeIf { it.isNotBlank() }?.asLocalSongId(),
            queue = s.queue.map { it.asLocalSongId() },
            queueIndex = s.queueIndex,
            title = s.title,
            artist = s.artist,
            album = s.album,
            artworkUri = s.coverArt,
            durationMs = s.durationMs,
            positionMs = s.positionMs,
            // Anchored on this device's own monotonic clock at the moment the update arrived,
            // not on a timestamp from the wire, which sidesteps clock skew between devices
            // entirely — the same reasoning as JamPosition.
            positionSampledAtElapsedMs = SystemClock.elapsedRealtime(),
            isPlaying = s.isPlaying,
            shuffle = s.shuffle,
            repeat = s.repeat,
            volume = s.volume,
            supportsVolume = s.volume != null,
            disallows = s.disallows,
        )
    }

    // ── Commands ───────────────────────────────────────────────────────────
    // targetUser resolves server-side to whichever device currently holds the session, so there
    // is no device id to pick and no staleness if the session moved a moment ago.

    private fun send(
        action: String,
        positionMs: Long? = null,
        volume: Float? = null,
        shuffle: Boolean? = null,
        repeat: String? = null,
    ) {
        if (!::scope.isInitialized) return
        scope.launch {
            jamManager.controlSelf(
                action = action,
                positionMs = positionMs,
                volume = volume,
                shuffle = shuffle,
                repeat = repeat,
            )
        }
    }

    private companion object {
        /** How NavidromeRepository.toSong() prefixes a gateway id locally. */
        const val LOCAL_ID_PREFIX = "navidrome_"
    }

    override fun play() = send("play")
    override fun pause() = send("pause")
    override fun next() = send("next")
    override fun previous() = send("previous")
    override fun seekTo(positionMs: Long) = send("seek", positionMs = positionMs)
    override fun setVolume(volume: Float) = send("volume", volume = volume)
    override fun setShuffle(enabled: Boolean) = send("shuffle", shuffle = enabled)
    override fun setRepeat(mode: String) = send("repeat", repeat = mode)
}
