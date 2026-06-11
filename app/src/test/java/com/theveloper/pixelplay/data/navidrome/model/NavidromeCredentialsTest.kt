package com.theveloper.pixelplay.data.navidrome.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NavidromeCredentialsTest {

    @Test
    fun `connectionValidationError accepts normalized https urls`() {
        val credentials = NavidromeCredentials(
            serverUrl = " https://music.example.com/subsonic/ ",
            username = "user",
            password = "pass"
        )

        assertNull(credentials.connectionValidationError())
        assertEquals("https://music.example.com/subsonic", credentials.normalizedServerUrl)
    }

    @Test
    fun `connectionValidationError accepts local http urls`() {
        val credentials = NavidromeCredentials(
            serverUrl = "http://192.168.1.20:4533",
            username = "user",
            password = "pass"
        )

        assertNull(credentials.connectionValidationError())
    }

    @Test
    fun `connectionValidationError rejects remote http urls`() {
        val credentials = NavidromeCredentials(
            serverUrl = "http://music.example.com",
            username = "user",
            password = "pass"
        )

        assertEquals(
            "Use an https:// server URL for remote Navidrome/Subsonic servers. HTTP is only allowed for local network addresses.",
            credentials.connectionValidationError()
        )
    }

    @Test
    fun `connectionValidationError rejects embedded credentials`() {
        val credentials = NavidromeCredentials(
            serverUrl = "https://user:secret@music.example.com",
            username = "user",
            password = "pass"
        )

        assertEquals(
            "Server URL must not include embedded credentials.",
            credentials.connectionValidationError()
        )
    }
}
