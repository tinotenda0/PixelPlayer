package com.theveloper.pixelplay.data.jam

/**
 * Which transport controls the active device cannot currently honour.
 *
 * A remote that shows every button always, and fires commands that quietly do nothing, is
 * lying to the user: a single-track queue still offers "previous", a live stream still offers
 * a scrubber. Spotify Connect solves this by shipping an `actions`/disallows object with the
 * player state so every controller can grey out what will not work, and this is the same idea
 * on this wire.
 *
 * Stated as *disallowed* rather than allowed so the empty set means "everything works" — which
 * is both the common case and the right reading of a client that sends nothing at all.
 */
internal object JamDisallows {
    const val SKIPPING_PREV = "skippingPrev"
    const val SKIPPING_NEXT = "skippingNext"
    const val SEEKING = "seeking"
    const val TOGGLING_SHUFFLE = "togglingShuffle"
    const val TOGGLING_REPEAT = "togglingRepeat"

    /**
     * Inverts command availability into the set of things that will not work.
     *
     * Takes plain booleans rather than a Player so it stays testable off-device; callers read
     * them from `Player.isCommandAvailable`.
     */
    fun derive(
        canSkipPrev: Boolean,
        canSkipNext: Boolean,
        canSeek: Boolean,
        canToggleShuffle: Boolean,
        canToggleRepeat: Boolean,
    ): Set<String> = buildSet {
        if (!canSkipPrev) add(SKIPPING_PREV)
        if (!canSkipNext) add(SKIPPING_NEXT)
        if (!canSeek) add(SEEKING)
        if (!canToggleShuffle) add(TOGGLING_SHUFFLE)
        if (!canToggleRepeat) add(TOGGLING_REPEAT)
    }
}
