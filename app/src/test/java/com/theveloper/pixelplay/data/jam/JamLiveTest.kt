package com.theveloper.pixelplay.data.jam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class JamLiveTest {

    @Test
    fun `signed out runs nothing, whatever else is true`() {
        assertNull(JamLiveMode.of(loggedIn = false, appInForeground = true, playing = true))
    }

    @Test
    fun `a visible app is foreground even while playing`() {
        assertEquals(
            JamLiveMode.FOREGROUND,
            JamLiveMode.of(loggedIn = true, appInForeground = true, playing = true),
        )
    }

    @Test
    fun `playing in the background keeps the connection`() {
        assertEquals(
            JamLiveMode.BACKGROUND_PLAYING,
            JamLiveMode.of(loggedIn = true, appInForeground = false, playing = true),
        )
    }

    @Test
    fun `backgrounded and silent is idle`() {
        assertEquals(
            JamLiveMode.IDLE,
            JamLiveMode.of(loggedIn = true, appInForeground = false, playing = false),
        )
    }

    @Test
    fun `backoff stays within the upper half of each exponential step`() {
        val steps = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)
        steps.forEachIndexed { i, step ->
            repeat(200) {
                val d = JamBackoff.delayMs(attempt = i + 1)
                assertTrue("attempt ${i + 1}: $d not in [${step / 2}, $step]", d in step / 2..step)
            }
        }
    }

    @Test
    fun `backoff is jittered, so devices dropped together do not retry together`() {
        val random = Random(42)
        val delays = List(50) { JamBackoff.delayMs(attempt = 4, random = random) }.toSet()
        assertTrue("expected spread, got $delays", delays.size > 10)
    }

    @Test
    fun `backoff never exceeds the cap however many attempts`() {
        assertTrue(JamBackoff.delayMs(attempt = 1_000) <= JamBackoff.MAX_DELAY_MS)
    }
}
