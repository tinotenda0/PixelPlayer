package com.theveloper.pixelplay.data.service.player

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * The single player behind [com.theveloper.pixelplay.data.service.MusicService]'s
 * `MediaLibrarySession`, standing between the session and whatever is actually producing sound.
 *
 * Everything that shows or controls playback reads that one session — the in-app mini and full
 * player (through the `MediaController` in `PlayerViewModel`), the notification, the lock
 * screen, widgets, Wear and Android Auto. So this is the only place a [PlaybackRoute] has to be
 * taught about for all of them to follow, which is the whole reason it exists: the alternative
 * was teaching each surface separately, the way Cast was wired in.
 *
 * **Right now it does nothing.** With no route active it is a plain pass-through to the local
 * player, deliberately, so that introducing it can be shipped and verified as a no-op before any
 * behaviour depends on it. Route awareness lands next.
 *
 * ### Why [ForwardingSimpleBasePlayer] and not `ForwardingPlayer`
 *
 * A plain `ForwardingPlayer` would be a simpler pass-through, but it is the wrong base for what
 * comes next: overriding its getters to report a remote device's state does not make anything
 * refresh, because Media3 only updates when listeners fire. Every state change would need its
 * own hand-written `onIsPlayingChanged`/`onMediaItemTransition`/`onEvents`, and the failure mode
 * of getting that wrong is a notification that silently goes stale while the in-app UI looks
 * fine. [ForwardingSimpleBasePlayer] instead takes a described state, diffs it, and emits the
 * right callbacks itself.
 *
 * That choice is also why this no-op stage is worth shipping on its own. The transparency being
 * verified is this base class's — a trivial pass-through would prove nothing about the wrapper
 * that actually gets used.
 *
 * ### Threading
 *
 * [ForwardingSimpleBasePlayer] inherits the wrapped player's application looper and asserts
 * calls arrive on it, matching what the session already required of the local player.
 */
@OptIn(UnstableApi::class)
class RoutingPlayer(localPlayer: Player) : ForwardingSimpleBasePlayer(localPlayer) {

    /** The local player currently being wrapped. */
    val localPlayer: Player
        get() = player

    /**
     * Swaps the local player being wrapped, keeping this instance — and therefore the session's
     * player — stable.
     *
     * [com.theveloper.pixelplay.data.service.player.DualPlayerEngine] crossfades between two
     * ExoPlayer instances and hands whichever one should be on show to the session, so the
     * session's player used to be reassigned mid-transition. Reassigning it now would drop this
     * wrapper and take any active route with it, so the swap happens underneath instead.
     */
    fun setLocalPlayer(player: Player) {
        if (player === this.player) return
        setPlayer(player)
    }
}
