package com.theveloper.pixelplay.data.jam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HandoffDeviceIdTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `the same id comes back after a restart`() {
        val first = HandoffDeviceId.load(tmp.root)
        val afterRestart = HandoffDeviceId.load(tmp.root)
        assertEquals(first, afterRestart)
        assertTrue(Regex("^[0-9a-f]{32}$").matches(first))
    }

    @Test
    fun `separate installs get separate ids`() {
        val a = HandoffDeviceId.load(tmp.newFolder("a"))
        val b = HandoffDeviceId.load(tmp.newFolder("b"))
        assertNotEquals(a, b)
    }

    @Test
    fun `a corrupt id file is replaced, and the replacement then sticks`() {
        File(tmp.root, "handoff_device_id").writeText("not-an-id")
        val replaced = HandoffDeviceId.load(tmp.root)
        assertTrue(Regex("^[0-9a-f]{32}$").matches(replaced))
        assertEquals(replaced, HandoffDeviceId.load(tmp.root))
    }

    @Test
    fun `a directory that does not exist yet is created`() {
        val dir = File(tmp.root, "no_backup")
        val id = HandoffDeviceId.load(dir)
        assertEquals(id, HandoffDeviceId.load(dir))
    }
}
