package com.theveloper.pixelplay.data.jam

/**
 * Ages a playback position that arrived from another device.
 *
 * A published [com.theveloper.pixelplay.data.navidrome.PlayerSessionState] is a *sample*: it was
 * true at the instant the active device sent it, and it goes stale at wall-clock rate from there.
 * Rather than have the active device re-send its position on a fast timer purely so the value
 * stays fresh, every receiver ages the sample it already holds — the same trick Spotify Connect
 * uses, where state carries a position plus the timestamp it was taken at and controllers
 * extrapolate locally.
 *
 * The anchor is the receiver's own monotonic clock at the moment the update arrived, not a
 * timestamp on the wire. That sidesteps clock skew between two phones entirely; the only error
 * left is the network delay between sampling and receipt, which is tens of milliseconds and
 * invisible on a progress bar. [android.os.SystemClock.elapsedRealtime] is the right clock
 * because it keeps counting while the device sleeps and cannot be moved by the user or NTP.
 *
 * Extrapolation is only valid while the sample says playback is running and nothing has moved
 * the playhead since. A seek, pause or track change on the active device invalidates it, which
 * is why those publish immediately instead of waiting for the next tick.
 */
internal object JamPosition {

    /**
     * @param sampledPositionMs position as published by the active device.
     * @param isPlaying whether that device was playing when it sampled.
     * @param receivedAtElapsedMs `elapsedRealtime` when this client received the sample, or 0 if
     *   unknown (no sample held yet) — in which case the position is returned unaged.
     * @param nowElapsedMs current `elapsedRealtime`.
     * @param durationMs track length, used to clamp; ignored when not positive (unknown).
     */
    fun extrapolate(
        sampledPositionMs: Long,
        isPlaying: Boolean,
        receivedAtElapsedMs: Long,
        nowElapsedMs: Long,
        durationMs: Long = 0L,
    ): Long {
        val sampled = sampledPositionMs.coerceAtLeast(0L)
        if (!isPlaying || receivedAtElapsedMs <= 0L) return clamp(sampled, durationMs)
        // A backwards clock reading would rewind the playhead; treat it as "no time passed".
        val agedMs = (nowElapsedMs - receivedAtElapsedMs).coerceAtLeast(0L)
        return clamp(sampled + agedMs, durationMs)
    }

    private fun clamp(positionMs: Long, durationMs: Long): Long =
        if (durationMs > 0L) positionMs.coerceIn(0L, durationMs) else positionMs.coerceAtLeast(0L)
}
