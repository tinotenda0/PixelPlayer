package com.theveloper.pixelplay.data.service.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What *this* device is playing, straight from the engine.
 *
 * Exists because the obvious source — a `MediaController` on the app's media session — is the
 * wrong one, in two separate ways:
 *
 * **It is not the whole queue.** [DualPlayerEngine] loads only a window of a large queue into
 * ExoPlayer and keeps the real one itself, which is why it offers `getFullQueue()` and
 * `getCurrentAbsoluteIndex()` at all. A controller's `mediaItemCount` is that window and its
 * `currentMediaItemIndex` is measured against it, so describing a queue that way hands another
 * device a fragment plus a number that means nothing outside this process.
 *
 * **It is not necessarily local.** [RoutingPlayer] presents whichever device owns playback
 * through that same session, so a controller reports the *remote* device while this one is
 * mirroring. Anything that publishes what it reads there ends up claiming a session it does not
 * own — and since the other device is doing the same, the two take it back and forth forever.
 *
 * So publication reads this, presentation reads the session, and nothing crosses back. That
 * direction of flow is the point: it makes the feedback loop unrepresentable rather than
 * guarded against.
 *
 * Published by [com.theveloper.pixelplay.data.service.MusicService] for as long as it is alive;
 * null means the service is not running and there is no local playback to speak of.
 */
@Singleton
class LocalPlayback @Inject constructor() {

    /** A snapshot of the engine's queue and where in it playback sits. */
    data class QueueSnapshot(val items: List<MediaItem>, val absoluteIndex: Int)

    @Volatile
    private var player: Player? = null

    @Volatile
    private var queueSource: (() -> QueueSnapshot)? = null

    /** Called by MusicService as the engine's player comes and goes, crossfades included. */
    fun attach(player: Player, queueSource: () -> QueueSnapshot) {
        this.player = player
        this.queueSource = queueSource
    }

    fun detach() {
        player = null
        queueSource = null
    }

    /**
     * The engine's player, for acting on local playback directly.
     *
     * Deliberately not a MediaController: those calls are asynchronous IPC handled long after
     * the caller returns, so a command meant for this device can be routed away in the gap.
     * Stopping because another device took the session is the case that has to work, and it is
     * exactly the case where a route is active.
     */
    fun player(): Player? = player

    /** The full queue and absolute position, or null when nothing is playing locally. */
    fun queue(): QueueSnapshot? = queueSource?.invoke()?.takeIf { it.items.isNotEmpty() }
}
