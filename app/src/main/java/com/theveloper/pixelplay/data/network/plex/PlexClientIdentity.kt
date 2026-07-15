package com.theveloper.pixelplay.data.network.plex

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The identity this install presents to the Plex ecosystem.
 *
 * The client identifier doubles as our Companion machineIdentifier: plex.tv
 * keys device records on it and controllers (Plexamp, Plex Web) address
 * commands to it, so it must be unique per install — a shared constant would
 * make two phones look like the same player.
 */
@Singleton
class PlexClientIdentity @Inject constructor(
    @ApplicationContext context: Context
) {
    companion object {
        const val PRODUCT = "PixelPlayer"
        const val VERSION = "1.0"
        const val PLATFORM = "Android"
        const val DEVICE_CLASS = "phone"
        const val PROTOCOL_VERSION = "3"
        const val PROTOCOL_CAPABILITIES = "timeline,playback,playqueues"
        const val PROVIDES = "client,player"

        private const val PREFS_NAME = "plex_identity"
        private const val KEY_CLIENT_ID = "client_identifier"
    }

    /** Stable per-install UUID; generated once and persisted. */
    val clientId: String

    /** User-visible player name, e.g. "PixelPlayer (Pixel 8)". */
    val deviceName: String = "$PRODUCT (${Build.MODEL})"

    val platformVersion: String = Build.VERSION.RELEASE ?: "0"

    init {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        clientId = prefs.getString(KEY_CLIENT_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_CLIENT_ID, it).apply()
        }
    }
}
