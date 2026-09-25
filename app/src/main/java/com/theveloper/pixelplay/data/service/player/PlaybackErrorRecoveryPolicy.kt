package com.theveloper.pixelplay.data.service.player

import androidx.media3.common.PlaybackException

/**
 * What to do with the queue after [androidx.media3.common.Player.Listener.onPlayerError].
 *
 * A mid-stream failure leaves the player in `STATE_IDLE` with no auto-transition, so without a
 * decision here playback simply stops until the user intervenes. One bad stream should not end
 * the queue.
 */
enum class PlaybackErrorRecovery {
    /** Re-prepare the same item and resume from where it died — a dropped connection, usually. */
    RETRY,

    /** This item has already had its retry, or was never going to play: move on. */
    SKIP_TO_NEXT,

    /** Nothing left to move on to. */
    STOP
}

/**
 * Only network/IO hiccups are worth a re-prepare. A missing file, a rejected request or a
 * malformed container fails again identically, so those skip straight on rather than burning a
 * retry the user has to sit through.
 */
internal fun isTransientPlaybackError(error: PlaybackException): Boolean = when (error.errorCode) {
    PlaybackException.ERROR_CODE_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> true

    else -> false
}

/**
 * Once this many tracks in a row have been given up on with no successful playback in between,
 * the problem is the setup rather than the track — an unreachable server, a dead proxy, no
 * network. Skipping on would silently burn through the whole queue, so stop and leave the user
 * looking at the track that failed.
 */
private const val MAX_ABANDONED_TRACKS_BEFORE_STOP = 3

/**
 * [alreadyRetried] is tracked per media item and [abandonedTracks] counts tracks skipped since
 * playback last succeeded; both are cleared once it does. So every item gets at most one retry,
 * a queue of dead streams does not loop on its first entry, and a systemic failure stops instead
 * of racing to the end of the queue.
 */
internal fun resolvePlaybackErrorRecovery(
    error: PlaybackException,
    alreadyRetried: Boolean,
    hasNextMediaItem: Boolean,
    abandonedTracks: Int
): PlaybackErrorRecovery = when {
    abandonedTracks >= MAX_ABANDONED_TRACKS_BEFORE_STOP -> PlaybackErrorRecovery.STOP
    !alreadyRetried && isTransientPlaybackError(error) -> PlaybackErrorRecovery.RETRY
    hasNextMediaItem -> PlaybackErrorRecovery.SKIP_TO_NEXT
    else -> PlaybackErrorRecovery.STOP
}
