package com.theveloper.pixelplay.data.plex.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PlexCredentialsTest {

    @Test
    fun `connectionValidationError accepts normalized https urls`() {
        val credentials = PlexCredentials(
            serverUrl = " https://media.example.com/plex/ ",
            username = "user",
            password = "pass"
        )

        assertNull(credentials.connectionValidationError())
        assertEquals("https://media.example.com/plex", credentials.normalizedServerUrl)
    }

    @Test
    fun `connectionValidationError accepts local http urls`() {
        val localUrls = listOf(
            "http://192.168.1.20:32400",
            "http://mynas:32400",
            "http://nas.lan:32400",
            "http://plex.home.arpa:32400",
            "http://[::1]:32400",
            "http://100.101.102.103:32400"
        )

        localUrls.forEach { url ->
            val credentials = PlexCredentials(
                serverUrl = url,
                username = "user",
                password = "pass"
            )

            assertNull(credentials.connectionValidationError(), "Expected $url to be accepted")
        }
    }

    @Test
    fun `connectionValidationError rejects remote http urls`() {
        val credentials = PlexCredentials(
            serverUrl = "http://media.example.com",
            username = "user",
            password = "pass"
        )

        assertEquals(
            "Use https:// for remote Plex servers. HTTP is only allowed for local network addresses.",
            credentials.connectionValidationError()
        )
    }

    @Test
    fun `connectionValidationError rejects embedded credentials`() {
        val credentials = PlexCredentials(
            serverUrl = "https://user:secret@media.example.com",
            username = "user",
            password = "pass"
        )

        assertEquals(
            "Server URL must not contain embedded credentials",
            credentials.connectionValidationError()
        )
    }
}
