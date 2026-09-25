package com.theveloper.pixelplay.data.jam

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("JamQueue")
class JamQueueTest {

    @Test
    fun `keeps the index when every id resolves`() {
        val sent = listOf("a", "b", "c", "d")
        assertEquals(2, JamQueue.alignStartIndex(sent, index = 2, resolvedIds = sent))
    }

    @Test
    fun `shifts the index back when an earlier id was dropped`() {
        // "b" did not resolve, so the track that was at index 2 is now at index 1.
        val result = JamQueue.alignStartIndex(
            sentIds = listOf("a", "b", "c", "d"), index = 2, resolvedIds = listOf("a", "c", "d")
        )
        assertEquals(1, result, "c moved up one slot when b was dropped")
    }

    @Test
    fun `ignores ids dropped after the start point`() {
        val result = JamQueue.alignStartIndex(
            sentIds = listOf("a", "b", "c", "d"), index = 1, resolvedIds = listOf("a", "b", "d")
        )
        assertEquals(1, result, "a drop after the start point cannot move it")
    }

    @Test
    fun `restarts the queue when the start track itself was dropped`() {
        // Falling back to the intended index would start on an arbitrary song; starting over is
        // wrong in a way the user can see and recover from.
        val result = JamQueue.alignStartIndex(
            sentIds = listOf("a", "b", "c"), index = 1, resolvedIds = listOf("a", "c")
        )
        assertEquals(0, result)
    }

    @Test
    fun `clamps an index past the end of the queue`() {
        val sent = listOf("a", "b")
        assertEquals(1, JamQueue.alignStartIndex(sent, index = 9, resolvedIds = sent))
    }

    @Test
    fun `clamps a negative index`() {
        val sent = listOf("a", "b")
        assertEquals(0, JamQueue.alignStartIndex(sent, index = -3, resolvedIds = sent))
    }

    @Test
    fun `handles songs that carry no gateway id`() {
        // getSongsByIds yields Song.navidromeId, which is nullable — a null must never match.
        val result = JamQueue.alignStartIndex(
            sentIds = listOf("a", "b"), index = 1, resolvedIds = listOf(null, "b")
        )
        assertEquals(1, result)
    }

    @Test
    fun `returns zero for an empty queue`() {
        assertEquals(0, JamQueue.alignStartIndex(emptyList(), index = 0, resolvedIds = listOf("a")))
        assertEquals(0, JamQueue.alignStartIndex(listOf("a"), index = 0, resolvedIds = emptyList()))
    }
}
