package com.theveloper.pixelplay.data.service.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which [PlaybackRoute], if any, currently owns playback.
 *
 * Deliberately a **single slot**. Cast and handoff can each believe they are in charge — you can
 * be casting while another of your devices claims the account's session — and two things
 * answering "what is playing" is how the player surface ends up contradicting itself. One slot
 * forces that conflict to be resolved here rather than surfacing as a UI that disagrees with
 * itself.
 *
 * Shared because the two sides of routing never meet otherwise: [RoutingPlayer] lives inside
 * `MusicService`, while `JamManager` reaches the same session from the app process through its
 * own `MediaController`.
 */
@Singleton
class PlaybackRouteRegistry @Inject constructor() {

    private val _activeRoute = MutableStateFlow<PlaybackRoute?>(null)

    /** The route owning playback, or null when it is happening on this device. */
    val activeRoute: StateFlow<PlaybackRoute?> = _activeRoute.asStateFlow()

    private val _suppressed = MutableStateFlow(false)

    /**
     * When true, [RoutingPlayer] ignores [activeRoute] and acts on the local player.
     *
     * This exists for one specific hazard. `JamManager` applies commands pushed to this device
     * through a `MediaController` bound to the very session [RoutingPlayer] sits behind, so a
     * command arriving while a route is still active would be sent straight back out to that
     * route instead of being played here — a loop, and a transfer that never lands.
     *
     * It does not normally happen: this device only receives commands when it *is* the active
     * device, and then no route is active. But the two facts arrive over the wire separately, so
     * a transfer's `play` can land a moment before the session update saying we now own
     * playback. That window is what this closes.
     */
    val suppressed: StateFlow<Boolean> = _suppressed.asStateFlow()

    /**
     * This device's own player, published by MusicService.
     *
     * For the handful of actions that must land *here* regardless of any route - stopping
     * because another device took the session, above all - reaching it directly is the only
     * thing that works. Going through a MediaController cannot: those calls are asynchronous
     * IPC, so the session handles them long after any flag set around the call has been
     * restored, and a still-active route forwards them to the very device we are reacting to.
     * That is how a superseded device ended up pausing its replacement while its own audio
     * carried on.
     */
    @Volatile
    var localPlayer: androidx.media3.common.Player? = null

    /**
     * The engine's authoritative queue and absolute position in it, published by MusicService.
     *
     * A MediaController does not see this. DualPlayerEngine loads only a window of a large
     * queue into ExoPlayer, so the controller's `mediaItemCount` is that window and its
     * `currentMediaItemIndex` is relative to it - which is why the engine keeps `getFullQueue`
     * and `getCurrentAbsoluteIndex` at all. Handing another device a window and an index
     * measured against it describes a queue that device cannot reconstruct.
     */
    @Volatile
    var queueView: (() -> Pair<List<androidx.media3.common.MediaItem>, Int>)? = null

    /** Makes [route] the one owning playback, replacing whatever held the slot. */
    fun register(route: PlaybackRoute) {
        _activeRoute.value = route
    }

    /** Gives the slot up, but only if [route] still holds it. */
    fun unregister(route: PlaybackRoute) {
        _activeRoute.compareAndSet(route, null)
    }

    /**
     * Runs [block] with routing suppressed, so anything it does through the media session acts
     * on the local player. Restores the previous value rather than clearing, so nesting is safe.
     */
    inline fun <T> withRoutingSuppressed(block: () -> T): T {
        val previous = setSuppressed(true)
        try {
            return block()
        } finally {
            setSuppressed(previous)
        }
    }

    /** Sets the suppression flag, returning what it was. Prefer [withRoutingSuppressed]. */
    fun setSuppressed(value: Boolean): Boolean {
        val previous = _suppressed.value
        _suppressed.value = value
        return previous
    }
}
