package com.theveloper.pixelplay.data.service.player

import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.theveloper.pixelplay.utils.MediaItemBuilder
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * The single player behind [com.theveloper.pixelplay.data.service.MusicService]'s
 * `MediaLibrarySession`, standing between the session and whatever is actually producing sound.
 *
 * Everything that shows or controls playback reads that one session — the in-app mini and full
 * player (through the `MediaController` in `PlayerViewModel`), the notification, the lock
 * screen, widgets, Wear and Android Auto. So a [PlaybackRoute] only has to be described here for
 * all of them to follow. That is the point: the alternative, and what Cast did, was teaching each
 * surface separately, which is why handoff previously needed a settings screen and transport
 * controls of its own instead of simply being the player.
 *
 * With no route active this is a pass-through and local playback is untouched.
 *
 * ### Why [ForwardingSimpleBasePlayer]
 *
 * A plain `ForwardingPlayer` would be a simpler wrapper, but reporting a remote device's state
 * by overriding its getters refreshes nothing: Media3 only updates when listeners fire, so every
 * field would need its own hand-written callback, and getting that wrong leaves the notification
 * silently stale while the in-app UI looks fine. [ForwardingSimpleBasePlayer] takes a described
 * state instead, diffs it against the last one, and emits the right callbacks itself. All this
 * class has to do is describe the route in [getState] and call `invalidateState()` when it
 * changes.
 */
@OptIn(UnstableApi::class)
class RoutingPlayer(
    localPlayer: Player,
    private val registry: PlaybackRouteRegistry,
    scope: CoroutineScope,
) : ForwardingSimpleBasePlayer(localPlayer) {

    /** The local player currently being wrapped. */
    val localPlayer: Player
        get() = player

    init {
        // Re-describe whenever the active route changes, or anything about what it is playing
        // does. This is the other half of the ForwardingSimpleBasePlayer bargain: it will diff
        // and notify correctly, but only when told the description is stale.
        scope.launch {
            combine(registry.activeRoute, registry.suppressed) { route, suppressed ->
                if (suppressed) null else route
            }
                .flatMapLatest { route ->
                    if (route == null) flowOf(null) else route.state
                }
                .distinctUntilChanged()
                .collect { invalidateState() }
        }
    }

    /**
     * Swaps the local player being wrapped, keeping this instance — and therefore the session's
     * player — stable.
     *
     * [DualPlayerEngine] crossfades between ExoPlayer instances and hands whichever should be on
     * show to the session, so the session's player used to be reassigned mid-transition. Doing
     * that now would drop this wrapper and any active route with it, so the swap happens
     * underneath instead.
     */
    fun setLocalPlayer(player: Player) {
        if (player === this.player) return
        setPlayer(player)
    }

    /** The route to obey right now, or null when playback belongs to this device. */
    private fun activeRoute(): PlaybackRoute? =
        if (registry.suppressed.value) null else registry.activeRoute.value

    override fun getState(): State {
        val route = activeRoute() ?: return super.getState()
        val routeState = route.state.value

        val queue = routeState.queue.ifEmpty { listOfNotNull(routeState.mediaId) }
        if (queue.isEmpty()) return super.getState()
        val index = routeState.queueIndex.coerceIn(0, queue.lastIndex)

        return State.Builder()
            .setAvailableCommands(availableCommands(routeState))
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(routeState.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaylist(queue.mapIndexed { i, id -> mediaItemData(id, i == index, routeState) })
            .setCurrentMediaItemIndex(index)
            .setContentPositionMs(positionSupplier(routeState))
            .setRepeatMode(repeatModeOf(routeState.repeat))
            .setShuffleModeEnabled(routeState.shuffle)
            .build()
    }

    /**
     * One entry in the route's queue.
     *
     * Only the playing track carries metadata — the session publishes ids for the others and
     * resolving every one of them would be a request per track just to fill a list. So the queue
     * is the right *length*, with the right item current, and the rest unnamed until something
     * resolves them.
     */
    private fun mediaItemData(
        mediaId: String,
        isCurrent: Boolean,
        routeState: RouteState,
    ): MediaItemData {
        val builder = MediaItemData.Builder(mediaId)
            .setMediaItem(MediaItem.Builder().setMediaId(mediaId).build())
            .setIsSeekable(!routeState.disallows.contains(DISALLOW_SEEKING))
            .setIsDynamic(false)

        if (isCurrent) {
            builder
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(routeState.title)
                        .setArtist(routeState.artist)
                        .setAlbumTitle(routeState.album)
                        .apply {
                            routeState.artworkUri
                                .takeIf { it.isNotBlank() }
                                ?.let { setArtworkUri(it.toUri()) }
                        }
                        // Enough for the app to build a Song from this item alone.
                        //
                        // The player surface resolves what the session reports back to a local
                        // Song before it will draw anything, and its last-resort mapper gives
                        // up unless the item carries a content uri. A device mirroring another
                        // one usually cannot satisfy that from its library - it may never have
                        // synced the track that is playing elsewhere - so without these the
                        // session reads correctly and the app still shows nothing.
                        //
                        // The uri is a marker, never fetched: while a route owns playback
                        // nothing here decodes, and taking playback back replaces these items
                        // wholesale with real ones (see JamManager.pullFrom).
                        .setExtras(
                            Bundle().apply {
                                putString(
                                    MediaItemBuilder.EXTERNAL_EXTRA_CONTENT_URI,
                                    REMOTE_ITEM_URI_PREFIX + mediaId,
                                )
                                putString(MediaItemBuilder.EXTERNAL_EXTRA_ALBUM, routeState.album)
                                putLong(
                                    MediaItemBuilder.EXTERNAL_EXTRA_DURATION,
                                    routeState.durationMs,
                                )
                            }
                        )
                        .build()
                )
                .setDurationUs(
                    if (routeState.durationMs > 0) routeState.durationMs * 1_000L
                    else C.TIME_UNSET
                )
        }
        return builder.build()
    }

    /**
     * A position that keeps moving between updates.
     *
     * What the route publishes is a *sample*, true when it was taken and stale from then on, so
     * it is aged to now before being handed over and then left to advance on its own. Without
     * that, a progress bar would sit still and jump every time the remote device reported in.
     */
    private fun positionSupplier(routeState: RouteState): SimpleBasePlayer.PositionSupplier {
        val sampledAt = routeState.positionSampledAtElapsedMs
        val agedMs = if (routeState.isPlaying && sampledAt > 0L) {
            routeState.positionMs + (SystemClock.elapsedRealtime() - sampledAt).coerceAtLeast(0L)
        } else {
            routeState.positionMs
        }.coerceIn(0L, if (routeState.durationMs > 0) routeState.durationMs else Long.MAX_VALUE)

        return if (routeState.isPlaying) {
            SimpleBasePlayer.PositionSupplier.getExtrapolating(agedMs, 1f)
        } else {
            SimpleBasePlayer.PositionSupplier.getConstant(agedMs)
        }
    }

    /**
     * What the user is allowed to press.
     *
     * The route says which controls it cannot honour — a single-track context has no previous, a
     * live stream has no scrubber — and those become unavailable commands rather than buttons
     * that quietly do nothing. Every surface reading the session greys them out for free.
     */
    private fun availableCommands(routeState: RouteState): Player.Commands {
        val builder = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_STOP,
        )
        if (!routeState.disallows.contains(DISALLOW_SEEKING)) {
            builder.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        }
        if (!routeState.disallows.contains(DISALLOW_SKIPPING_NEXT)) {
            builder.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }
        if (!routeState.disallows.contains(DISALLOW_SKIPPING_PREV)) {
            builder.addAll(
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
        }
        if (!routeState.disallows.contains(DISALLOW_TOGGLING_SHUFFLE)) {
            builder.add(Player.COMMAND_SET_SHUFFLE_MODE)
        }
        if (!routeState.disallows.contains(DISALLOW_TOGGLING_REPEAT)) {
            builder.add(Player.COMMAND_SET_REPEAT_MODE)
        }
        if (routeState.supportsVolume) {
            builder.add(Player.COMMAND_SET_VOLUME)
        }
        return builder.build()
    }

    private fun repeatModeOf(repeat: String): Int = when (repeat) {
        "one", "track" -> Player.REPEAT_MODE_ONE
        "all", "context" -> Player.REPEAT_MODE_ALL
        else -> Player.REPEAT_MODE_OFF
    }

    // ── Commands ───────────────────────────────────────────────────────────
    // Each of these acts on the route when one owns playback, and otherwise falls through to
    // the local player. The route's own state update is what moves the UI, not the call
    // returning — the remote device is the authority on whether it actually happened.

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val route = activeRoute() ?: return super.handleSetPlayWhenReady(playWhenReady)
        if (playWhenReady) route.play() else route.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        val route = activeRoute()
            ?: return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> route.next()

            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> route.previous()

            else -> route.seekTo(positionMs.coerceAtLeast(0L))
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        val route = activeRoute() ?: return super.handleSetRepeatMode(repeatMode)
        route.setRepeat(
            when (repeatMode) {
                Player.REPEAT_MODE_ONE -> "one"
                Player.REPEAT_MODE_ALL -> "all"
                else -> "off"
            }
        )
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        val route = activeRoute() ?: return super.handleSetShuffleModeEnabled(shuffleModeEnabled)
        route.setShuffle(shuffleModeEnabled)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> {
        val route = activeRoute()
            ?: return super.handleSetVolume(volume, volumeOperationType)
        route.setVolume(volume.coerceIn(0f, 1f))
        return Futures.immediateVoidFuture()
    }

    private companion object {
        /** Marks an item as playing on another device. Never fetched - see mediaItemData. */
        const val REMOTE_ITEM_URI_PREFIX = "pixelplay://remote/"

        // Mirrors com.theveloper.pixelplay.data.jam.JamDisallows, which is the wire vocabulary.
        // Duplicated rather than depended on so the routing layer stays independent of handoff.
        const val DISALLOW_SKIPPING_PREV = "skippingPrev"
        const val DISALLOW_SKIPPING_NEXT = "skippingNext"
        const val DISALLOW_SEEKING = "seeking"
        const val DISALLOW_TOGGLING_SHUFFLE = "togglingShuffle"
        const val DISALLOW_TOGGLING_REPEAT = "togglingRepeat"
    }
}
