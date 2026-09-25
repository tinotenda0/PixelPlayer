package com.theveloper.pixelplay.data.jam

/**
 * Keeps a handed-off queue's start point pointing at the track it was meant to.
 *
 * A transfer sends the sender's *whole* queue plus the index it is currently on, rather than
 * just the part from there on, so the receiving device keeps the already-played tracks as
 * history it can skip back into — the way a Spotify Connect handoff does.
 *
 * The catch is that the index is stated over the ids that were *sent*, and
 * [com.theveloper.pixelplay.data.navidrome.NavidromeRepository.getSongsByIds] silently drops
 * any id the server cannot resolve. It preserves order, so the queue itself survives, but the
 * positions shift: one dropped id before the start point and playback would begin on the wrong
 * song. Re-finding the intended track by id is the only alignment that holds.
 */
internal object JamQueue {

    /**
     * The index of `sentIds[index]` within [resolvedIds], or 0 if that track did not resolve.
     *
     * Falling back to the top of the queue rather than to [index] is deliberate: an index that
     * no longer refers to the intended track is not "close enough", it points at an arbitrary
     * song. Restarting the queue is wrong in an obvious, recoverable way; silently playing the
     * wrong track is wrong in a way the user has to diagnose.
     *
     * @param sentIds the queue as put on the wire.
     * @param index the position in [sentIds] playback should start at; clamped if out of range.
     * @param resolvedIds the ids that actually came back, in order — a subsequence of [sentIds].
     */
    fun alignStartIndex(sentIds: List<String>, index: Int, resolvedIds: List<String?>): Int {
        if (sentIds.isEmpty() || resolvedIds.isEmpty()) return 0
        val startId = sentIds[index.coerceIn(0, sentIds.lastIndex)]
        return resolvedIds.indexOf(startId).takeIf { it >= 0 } ?: 0
    }
}
