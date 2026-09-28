package com.theveloper.pixelplay.data.service.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where playback currently is: another device, a Cast receiver, or here.
 *
 * Deliberately a **single slot**. Cast and handoff can each believe they are in charge - you can
 * be casting while another of your devices claims the account's session - and two things
 * answering "what is playing" is how the player surface ends up contradicting itself. One slot
 * forces that conflict to be resolved here rather than surfacing as a UI that disagrees with
 * itself.
 *
 * Paired with [LocalPlayback], which answers the other question: what *this* device is playing.
 * Publication reads that one, presentation reads this one, and nothing crosses back. That is
 * what stops a mirroring device from publishing the state it is mirroring and claiming a
 * session it does not own.
 */
@Singleton
class PlaybackRouteRegistry @Inject constructor() {

    private val _activeRoute = MutableStateFlow<PlaybackRoute?>(null)

    /** The route owning playback, or null when it is happening on this device. */
    val activeRoute: StateFlow<PlaybackRoute?> = _activeRoute.asStateFlow()

    /** Makes [route] the one owning playback, replacing whatever held the slot. */
    fun register(route: PlaybackRoute) {
        _activeRoute.value = route
    }

    /** Gives the slot up, but only if [route] still holds it. */
    fun unregister(route: PlaybackRoute) {
        _activeRoute.compareAndSet(route, null)
    }
}
