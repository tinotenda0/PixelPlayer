package com.theveloper.pixelplay.data.jam

import kotlin.random.Random

/**
 * How much of the live session machinery this device should be running right now.
 *
 * The push connection is an open socket with a server keepalive every ~20s, and each keepalive
 * wakes the radio. That is worth paying while someone can see the result (the app is open) or
 * while this device is the one playing (it must stay controllable from the others). An idle
 * device in the background pays it for nothing — so after [IDLE_DISCONNECT_GRACE_MS] it drops
 * the connection, and picks it back up the moment either condition returns.
 */
internal enum class JamLiveMode {
    /** App visible: live connection plus the periodic device-list refresh the UI shows. */
    FOREGROUND,

    /** Backgrounded but producing audio: live connection only, so remotes keep working. */
    BACKGROUND_PLAYING,

    /** Backgrounded and silent: disconnect once the grace period has passed. */
    IDLE;

    companion object {
        /**
         * How long an idle, backgrounded device stays reachable. Long enough that a pause (a
         * phone call, stepping away) can still be resumed from another device, or a transfer
         * can still land on this one; short enough that a phone left in a pocket stops
         * waking its radio for a connection nobody is using.
         */
        const val IDLE_DISCONNECT_GRACE_MS = 10 * 60_000L

        /** Null when signed out: there is no gateway session to run at all. */
        fun of(loggedIn: Boolean, appInForeground: Boolean, playing: Boolean): JamLiveMode? = when {
            !loggedIn -> null
            appInForeground -> FOREGROUND
            playing -> BACKGROUND_PLAYING
            else -> IDLE
        }
    }
}

/** Reconnect delays for the live connection. */
internal object JamBackoff {
    const val MAX_DELAY_MS = 30_000L

    /**
     * Exponential backoff (1s, 2s, 4s … capped at [MAX_DELAY_MS]) with "equal jitter": the delay
     * is a random point in the upper half of that step. Without jitter, every device dropped by
     * the same event — a backend deploy restarts the server and cuts every connection at once —
     * would retry in lockstep, and keep colliding on each retry.
     */
    fun delayMs(attempt: Int, random: Random = Random.Default): Long {
        val step = (1000L shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(MAX_DELAY_MS)
        val half = step / 2
        return half + random.nextLong(half + 1)
    }
}
