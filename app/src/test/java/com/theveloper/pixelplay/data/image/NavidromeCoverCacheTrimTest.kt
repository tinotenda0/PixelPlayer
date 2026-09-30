package com.theveloper.pixelplay.data.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NavidromeCoverCacheTrimTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 10_000_000L

    private fun cover(name: String, bytes: Int, modifiedAt: Long): File =
        File(tmp.root, NavidromeCoilFetcher.COVER_FILE_PREFIX + name).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(modifiedAt)
        }

    @Test
    fun `evicts least recently used covers until under the cap`() {
        val oldest = cover("a_500.jpg", 100, now - 3_000)
        val middle = cover("b_500.jpg", 100, now - 2_000)
        val newest = cover("c_500.jpg", 100, now - 1_000)

        NavidromeCoilFetcher.trimCoverCache(tmp.root, maxBytes = 150, nowMs = now)

        assertFalse(oldest.exists())
        assertFalse(middle.exists())
        assertTrue(newest.exists())
    }

    @Test
    fun `leaves the cache alone when it fits`() {
        val a = cover("a_500.jpg", 100, now - 3_000)
        NavidromeCoilFetcher.trimCoverCache(tmp.root, maxBytes = 1_000, nowMs = now)
        assertTrue(a.exists())
    }

    @Test
    fun `never touches files that are not covers`() {
        val other = File(tmp.root, "media_stream_cache_index").apply {
            writeBytes(ByteArray(10_000))
            setLastModified(now - 1_000_000)
        }
        cover("a_500.jpg", 100, now)
        NavidromeCoilFetcher.trimCoverCache(tmp.root, maxBytes = 0, nowMs = now)
        assertTrue(other.exists())
    }

    @Test
    fun `sweeps abandoned temp files but not ones still being written`() {
        val abandoned = cover("a_500.jpg.123.tmp", 10, now - NavidromeCoilFetcher.STALE_TMP_MS - 1)
        val inProgress = cover("b_500.jpg.456.tmp", 10, now - 100)

        NavidromeCoilFetcher.trimCoverCache(tmp.root, maxBytes = 1_000, nowMs = now)

        assertFalse(abandoned.exists())
        assertTrue(inProgress.exists())
        assertEquals(1, tmp.root.listFiles()!!.size)
    }
}
