package com.theveloper.pixelplay.data.jam

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("JamDisallows")
class JamDisallowsTest {

    private fun derive(
        canSkipPrev: Boolean = true,
        canSkipNext: Boolean = true,
        canSeek: Boolean = true,
        canToggleShuffle: Boolean = true,
        canToggleRepeat: Boolean = true,
    ) = JamDisallows.derive(
        canSkipPrev, canSkipNext, canSeek, canToggleShuffle, canToggleRepeat
    )

    @Test
    fun `a fully capable player disallows nothing`() {
        assertTrue(derive().isEmpty(), "empty is what a remote reads as 'everything works'")
    }

    @Test
    fun `names only the controls that will not work`() {
        val result = derive(canSkipPrev = false, canSeek = false)
        assertEquals(setOf(JamDisallows.SKIPPING_PREV, JamDisallows.SEEKING), result)
    }

    @Test
    fun `a single-track queue disallows both skips`() {
        val result = derive(canSkipPrev = false, canSkipNext = false)
        assertEquals(setOf(JamDisallows.SKIPPING_PREV, JamDisallows.SKIPPING_NEXT), result)
    }

    @Test
    fun `a player that can do nothing disallows everything`() {
        val result = derive(
            canSkipPrev = false, canSkipNext = false, canSeek = false,
            canToggleShuffle = false, canToggleRepeat = false,
        )
        assertEquals(
            setOf(
                JamDisallows.SKIPPING_PREV, JamDisallows.SKIPPING_NEXT, JamDisallows.SEEKING,
                JamDisallows.TOGGLING_SHUFFLE, JamDisallows.TOGGLING_REPEAT,
            ),
            result,
        )
    }

    @Test
    fun `names are stable wire values`() {
        // These cross the network to the PWA and to older builds, so they are API, not labels.
        assertEquals("skippingPrev", JamDisallows.SKIPPING_PREV)
        assertEquals("skippingNext", JamDisallows.SKIPPING_NEXT)
        assertEquals("seeking", JamDisallows.SEEKING)
        assertEquals("togglingShuffle", JamDisallows.TOGGLING_SHUFFLE)
        assertEquals("togglingRepeat", JamDisallows.TOGGLING_REPEAT)
    }
}
