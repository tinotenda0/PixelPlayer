package com.theveloper.pixelplay.data.service.player

import kotlinx.coroutines.flow.StateFlow

/**
 * Somewhere other than this device's speakers that playback can be happening.
 *
 * PixelPlayer has two of these — a Cast receiver and another of your own devices holding the
 * handoff session — and until now each was wired in separately, with its own conditionals
 * through the state holders and its own remote-control UI. That is why handoff never felt like
 * Spotify Connect: the player surface only ever described the *local* player, so an idle device
 * showed its own stale track and needed a bespoke screen to control anything else.
 *
 * A route inverts that. It describes what is playing wherever playback actually is, and accepts
 * the transport commands the user presses here. [RoutingPlayer] presents whichever route is
 * active as the player behind the one `MediaLibrarySession`, so every surface that reads the
 * session — the mini and full player, the notification, the lock screen, widgets, Wear, Android
 * Auto — follows without knowing a route exists.
 *
 * Not yet implemented by anything: [RoutingPlayer] currently always plays locally. The shape
 * comes from the fields already crossing the wire (see `JamState`), so it is grounded in real
 * data rather than guessed at, but expect it to move once `HandoffRoute` is written against it.
 */
interface PlaybackRoute {

    /** Human-readable name of where the audio is coming out, e.g. "Pixel 8" or a speaker. */
    val name: String

    /**
     * Whether this route currently owns playback. A route that is merely reachable is not
     * active; exactly one route may be active at a time, and when none is, playback is local.
     */
    val isActive: StateFlow<Boolean>

    /** What the route is playing right now, or null if it has nothing loaded. */
    val state: StateFlow<RouteState>

    fun play()
    fun pause()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun setVolume(volume: Float)
    fun setShuffle(enabled: Boolean)
    fun setRepeat(mode: String)
}

/**
 * A snapshot of what a route is playing.
 *
 * [positionMs] is a *sample*, true at [positionSampledAtElapsedMs] and stale thereafter —
 * receivers age it rather than having the source re-send on a fast timer (see
 * `JamPosition.extrapolate`, which already does exactly this for the handoff session).
 */
data class RouteState(
    val mediaId: String? = null,
    /**
     * Every track in the route's queue, and where in it playback sits.
     *
     * Carried because the transport controls depend on it: a player told it has one item cannot
     * offer next or previous. Only the track at [queueIndex] comes with metadata — the session
     * publishes ids for the rest — so the others are present but unnamed.
     */
    val queue: List<String> = emptyList(),
    val queueIndex: Int = 0,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUri: String = "",
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    /** `SystemClock.elapsedRealtime` when [positionMs] was sampled, or 0 if never. */
    val positionSampledAtElapsedMs: Long = 0L,
    val isPlaying: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: String = "off",
    /** 0f..1f, or null when the route has not reported a level. */
    val volume: Float? = null,
    /** Whether the route can change its own output level at all. */
    val supportsVolume: Boolean = false,
    /**
     * Transport controls this route cannot honour right now, as published in the handoff
     * protocol (see `JamDisallows`). These become unavailable `Player` commands, so every
     * surface greys them out instead of offering a button that quietly does nothing.
     */
    val disallows: Set<String> = emptySet(),
)
