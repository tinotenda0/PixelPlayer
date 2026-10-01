package com.theveloper.pixelplay.data.service.player

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingPlayerPlaylistUidsTest {

    @Test
    fun `a queue without repeats keeps the plain ids`() {
        assertEquals(listOf("yt-a", "yt-b", "yt-c"), playlistUids(listOf("yt-a", "yt-b", "yt-c")))
    }

    @Test
    fun `repeated songs get distinct uids`() {
        val uids = playlistUids(listOf("yt-a", "yt-b", "yt-a", "yt-a"))
        assertEquals(listOf("yt-a", "yt-b", "yt-a#2", "yt-a#3"), uids)
        assertEquals(uids.size, uids.toSet().size)
    }

    @Test
    fun `uids of unrepeated songs stay stable when the queue grows`() {
        val before = playlistUids(listOf("yt-a", "yt-b"))
        val after = playlistUids(listOf("yt-a", "yt-b", "yt-c", "yt-a"))
        assertEquals(before, after.take(2))
    }
}
