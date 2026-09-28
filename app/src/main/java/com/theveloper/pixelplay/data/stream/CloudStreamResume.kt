package com.theveloper.pixelplay.data.stream

/**
 * Deciding how to pick a stream back up when the upstream body dies partway through.
 *
 * The proxy hands ExoPlayer one long-lived response per track. Anything that kills the upstream
 * socket mid-body — a cell handover while driving, a wifi-to-cellular switch, a stalled read —
 * used to end that response truncated, which ExoPlayer reports as a source error even though the
 * network is perfectly healthy a second later. Re-requesting the remainder by byte offset and
 * continuing to write into the same response keeps the failure invisible to the player.
 */
object CloudStreamResume {

    /**
     * Attempts per response, sized to ride out a blip rather than an outage: the backoff below
     * spans roughly seven seconds, which covers a cell handover or a wifi-to-cellular switch.
     * Anything longer is a real loss of connectivity, and the player's own recovery handles that
     * better than retrying here would — it can wait indefinitely and resume at the right position.
     */
    const val MAX_ATTEMPTS = 4

    /** Exponential backoff — 500ms, 1s, 2s, 4s — giving the new network path time to settle. */
    fun backoffMs(attempt: Int): Long = 500L shl (attempt.coerceIn(1, MAX_ATTEMPTS) - 1)

    /**
     * Range header covering what is left to deliver.
     *
     * [clientStart] is the absolute offset the player asked for (0 when it sent no Range), and
     * [deliveredBytes] is how much of that has already been written, so the remainder starts at
     * their sum. A bounded request keeps its original end so the resume cannot overrun it.
     */
    fun resumeRangeHeader(
        clientStart: Long,
        clientEndInclusive: Long?,
        deliveredBytes: Long
    ): String {
        val from = clientStart + deliveredBytes
        return if (clientEndInclusive != null) "bytes=$from-$clientEndInclusive" else "bytes=$from-"
    }

    /**
     * A resume is only safe when the offset to resume from is actually known.
     *
     * A suffix range ("bytes=-500", the last 500 bytes) has no absolute start until the total
     * length is known, so its remainder cannot be addressed and it is left to fail as before.
     * Nothing delivered yet means the body never started, which the connection-level retry in
     * OkHttp already covers.
     */
    fun canResume(isSuffixRange: Boolean, deliveredBytes: Long, attempt: Int): Boolean =
        !isSuffixRange && deliveredBytes > 0L && attempt < MAX_ATTEMPTS
}
