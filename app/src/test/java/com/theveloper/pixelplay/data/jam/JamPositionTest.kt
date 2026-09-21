package com.theveloper.pixelplay.data.jam

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("JamPosition")
class JamPositionTest {

    @Test
    fun `ages a playing sample by the time since it arrived`() {
        val result = JamPosition.extrapolate(
            sampledPositionMs = 30_000L,
            isPlaying = true,
            receivedAtElapsedMs = 1_000L,
            nowElapsedMs = 26_000L,
            durationMs = 200_000L,
        )
        assertEquals(55_000L, result, "25s elapsed since a 30s sample")
    }

    @Test
    fun `a paused sample does not advance`() {
        val result = JamPosition.extrapolate(
            sampledPositionMs = 30_000L,
            isPlaying = false,
            receivedAtElapsedMs = 1_000L,
            nowElapsedMs = 61_000L,
            durationMs = 200_000L,
        )
        assertEquals(30_000L, result)
    }

    @Test
    fun `an unanchored sample is returned unaged`() {
        // No receipt stamp held (nothing received yet) — ageing would be guesswork.
        val result = JamPosition.extrapolate(
            sampledPositionMs = 30_000L,
            isPlaying = true,
            receivedAtElapsedMs = 0L,
            nowElapsedMs = 61_000L,
            durationMs = 200_000L,
        )
        assertEquals(30_000L, result)
    }

    @Test
    fun `never runs past the end of the track`() {
        val result = JamPosition.extrapolate(
            sampledPositionMs = 190_000L,
            isPlaying = true,
            receivedAtElapsedMs = 1_000L,
            nowElapsedMs = 61_000L,
            durationMs = 200_000L,
        )
        assertEquals(200_000L, result, "clamped to duration rather than overrunning")
    }

    @Test
    fun `unknown duration is left unclamped`() {
        val result = JamPosition.extrapolate(
            sampledPositionMs = 190_000L,
            isPlaying = true,
            receivedAtElapsedMs = 1_000L,
            nowElapsedMs = 61_000L,
            durationMs = 0L,
        )
        assertEquals(250_000L, result)
    }

    @Test
    fun `a backwards clock reading does not rewind the playhead`() {
        val result = JamPosition.extrapolate(
            sampledPositionMs = 30_000L,
            isPlaying = true,
            receivedAtElapsedMs = 10_000L,
            nowElapsedMs = 9_000L,
            durationMs = 200_000L,
        )
        assertEquals(30_000L, result)
    }

    @Test
    fun `a negative sample is floored at zero`() {
        val result = JamPosition.extrapolate(
            sampledPositionMs = -5_000L,
            isPlaying = false,
            receivedAtElapsedMs = 0L,
            nowElapsedMs = 0L,
        )
        assertEquals(0L, result)
    }
}
