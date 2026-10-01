package com.theveloper.pixelplay.data.jam

import java.io.File
import java.util.UUID

/**
 * This device's handoff identity, stable across process restarts.
 *
 * It used to be a fresh UUID per process, so after any restart the gateway still held the
 * previous process's session under a different id — and this device took its own earlier self
 * for another device: it mirrored that session (a repeated song in its queue crashed the app),
 * and, believing playback lived elsewhere, stopped publishing its own until the old entry
 * expired.
 *
 * Kept in `noBackupFilesDir` deliberately: Android backup must not carry it to a new phone, or
 * two devices would share one identity and keep superseding each other.
 */
internal object HandoffDeviceId {
    private const val FILE_NAME = "handoff_device_id"
    private val VALID = Regex("^[0-9a-f]{32}$")

    fun load(dir: File): String {
        val file = File(dir, FILE_NAME)
        runCatching { file.readText().trim() }.getOrNull()
            ?.takeIf { VALID.matches(it) }
            ?.let { return it }

        val id = UUID.randomUUID().toString().replace("-", "")
        runCatching {
            dir.mkdirs()
            // Temp + rename, so a crash mid-write can't leave a truncated id that would be
            // replaced (with a different one) on the next start.
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(id)
            if (!tmp.renameTo(file)) tmp.delete()
        }
        return id
    }
}
