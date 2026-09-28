package com.theveloper.pixelplay.data.service.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [LocalPlayback] exists so that what a device publishes about itself comes from the engine
 * rather than from a MediaController. These pin the two ways the controller lied.
 */
@DisplayName("LocalPlayback")
class LocalPlaybackTest {

    private val localPlayback = LocalPlayback()

    private fun items(vararg ids: String) = ids.map { MediaItem.Builder().setMediaId(it).build() }

    @Test
    fun `nothing is playing locally until the service attaches`() {
        assertNull(localPlayback.player())
        assertNull(localPlayback.queue())
    }

    @Test
    fun `reports the engine's whole queue, not a window of it`() {
        // DualPlayerEngine loads only a window of a large queue into ExoPlayer, so a controller
        // would report that window. Publishing it hands another device part of the queue.
        val full = items("a", "b", "c", "d", "e")
        localPlayback.attach(mockk<Player>(relaxed = true)) { LocalPlayback.QueueSnapshot(full, 3) }

        val snapshot = requireNotNull(localPlayback.queue())
        assertEquals(5, snapshot.items.size)
        assertEquals("d", snapshot.items[snapshot.absoluteIndex].mediaId)
    }

    @Test
    fun `reports the absolute index, not one measured against a window`() {
        // The track playing is the 200th of the queue while sitting at index 2 of the window.
        // Publishing 2 is what made a receiver resume several tracks earlier, permanently.
        val full = items(*Array(300) { "t$it" })
        localPlayback.attach(mockk<Player>(relaxed = true)) { LocalPlayback.QueueSnapshot(full, 199) }

        val snapshot = requireNotNull(localPlayback.queue())
        assertEquals(199, snapshot.absoluteIndex)
        assertEquals("t199", snapshot.items[snapshot.absoluteIndex].mediaId)
    }

    @Test
    fun `an empty queue reads as nothing playing`() {
        localPlayback.attach(mockk<Player>(relaxed = true)) { LocalPlayback.QueueSnapshot(emptyList(), 0) }
        assertNull(localPlayback.queue())
    }

    @Test
    fun `detaching leaves nothing behind`() {
        localPlayback.attach(mockk<Player>(relaxed = true)) { LocalPlayback.QueueSnapshot(items("a"), 0) }
        localPlayback.detach()
        assertNull(localPlayback.player())
        assertNull(localPlayback.queue())
    }
}
