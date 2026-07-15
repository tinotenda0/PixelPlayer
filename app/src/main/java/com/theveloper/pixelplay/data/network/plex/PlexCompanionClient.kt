package com.theveloper.pixelplay.data.network.plex

import android.util.Xml
import com.theveloper.pixelplay.data.plex.model.PlexPlayerDevice
import com.theveloper.pixelplay.data.plex.model.PlexRemoteTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plex Companion controller client — the protocol Plexamp instances use to
 * remote-control each other. Commands are plain HTTP GETs against the target
 * player (Plexamp listens on port 32500); timelines come back as XML.
 */
@Singleton
class PlexCompanionClient @Inject constructor(
    baseOkHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "PlexCompanion"
        private const val CLIENT_IDENTIFIER = "PixelPlayer-Android"
        private const val CLIENT_NAME = "PixelPlayer"
    }

    private val httpClient: OkHttpClient = baseOkHttpClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    // Companion requires a monotonically increasing commandID per target.
    private val commandIds = ConcurrentHashMap<String, AtomicInteger>()

    private fun nextCommandId(clientIdentifier: String): Int =
        commandIds.getOrPut(clientIdentifier) { AtomicInteger(0) }.incrementAndGet()

    private fun Request.Builder.withCompanionHeaders(
        targetClientIdentifier: String,
        token: String?
    ): Request.Builder {
        header("X-Plex-Client-Identifier", CLIENT_IDENTIFIER)
        header("X-Plex-Device-Name", CLIENT_NAME)
        header("X-Plex-Product", CLIENT_NAME)
        header("X-Plex-Target-Client-Identifier", targetClientIdentifier)
        token?.let { header("X-Plex-Token", it) }
        return this
    }

    /** Cheap reachability probe of a candidate player address. */
    suspend fun isReachable(uri: String, clientIdentifier: String, token: String?): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("${uri.trimEnd('/')}/resources")
                    .withCompanionHeaders(clientIdentifier, token)
                    .get()
                    .build()
                httpClient.newCall(request).execute().use { it.isSuccessful }
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Send a playback command: "play", "pause", "stop", "skipNext", "skipPrevious".
     */
    suspend fun sendPlaybackCommand(
        device: PlexPlayerDevice,
        command: String,
        token: String?
    ): Result<Unit> {
        return get(
            device = device,
            path = "/player/playback/$command",
            params = mapOf("type" to "music"),
            token = token
        ).map { }
    }

    suspend fun setVolume(device: PlexPlayerDevice, volume: Int, token: String?): Result<Unit> {
        return get(
            device = device,
            path = "/player/playback/setParameters",
            params = mapOf(
                "type" to "music",
                "volume" to volume.coerceIn(0, 100).toString()
            ),
            token = token
        ).map { }
    }

    suspend fun seekTo(device: PlexPlayerDevice, positionMs: Long, token: String?): Result<Unit> {
        return get(
            device = device,
            path = "/player/playback/seekTo",
            params = mapOf(
                "type" to "music",
                "offset" to positionMs.coerceAtLeast(0L).toString()
            ),
            token = token
        ).map { }
    }

    /**
     * Point the remote player at a play queue on the media server.
     */
    suspend fun playMedia(
        device: PlexPlayerDevice,
        serverUrl: String,
        serverMachineIdentifier: String,
        serverToken: String,
        playQueueId: Long,
        trackKey: String,
        offsetMs: Long = 0L,
        token: String?
    ): Result<Unit> {
        val parsedServer = serverUrl.toHttpUrlOrNull()
            ?: return Result.failure(Exception("Invalid server URL"))

        return get(
            device = device,
            path = "/player/playback/playMedia",
            params = mapOf(
                "type" to "music",
                "key" to trackKey,
                "offset" to offsetMs.coerceAtLeast(0L).toString(),
                "machineIdentifier" to serverMachineIdentifier,
                "protocol" to parsedServer.scheme,
                "address" to parsedServer.host,
                "port" to parsedServer.port.toString(),
                "token" to serverToken,
                "containerKey" to "/playQueues/$playQueueId?own=1"
            ),
            token = token
        ).map { }
    }

    /**
     * Current music timeline of the remote player, or null when it reports
     * nothing for music.
     */
    suspend fun getTimeline(device: PlexPlayerDevice, token: String?): Result<PlexRemoteTimeline?> {
        return get(
            device = device,
            path = "/player/timeline/poll",
            params = mapOf("wait" to "0"),
            token = token
        ).mapCatching { body -> parseMusicTimeline(body) }
    }

    private suspend fun get(
        device: PlexPlayerDevice,
        path: String,
        params: Map<String, String>,
        token: String?
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val urlBuilder = "${device.uri.trimEnd('/')}$path".toHttpUrl().newBuilder()
                    .addQueryParameter("commandID", nextCommandId(device.clientIdentifier).toString())
                params.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }

                val request = Request.Builder()
                    .url(urlBuilder.build())
                    .withCompanionHeaders(device.clientIdentifier, token)
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            Exception("HTTP ${response.code}: ${response.message}")
                        )
                    }
                    Result.success(response.body.string())
                }
            } catch (e: Exception) {
                Timber.w(e, "$TAG: $path failed for ${device.name}")
                Result.failure(e)
            }
        }
    }

    private fun parseMusicTimeline(xml: String): PlexRemoteTimeline? {
        if (xml.isBlank()) return null
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "Timeline" &&
                parser.getAttributeValue(null, "type") == "music"
            ) {
                val state = parser.getAttributeValue(null, "state") ?: "stopped"
                return PlexRemoteTimeline(
                    state = state,
                    timeMs = parser.getAttributeValue(null, "time")?.toLongOrNull() ?: 0L,
                    durationMs = parser.getAttributeValue(null, "duration")?.toLongOrNull() ?: 0L,
                    ratingKey = parser.getAttributeValue(null, "ratingKey")?.takeIf { it.isNotBlank() },
                    machineIdentifier = parser.getAttributeValue(null, "machineIdentifier")
                        ?.takeIf { it.isNotBlank() },
                    volume = parser.getAttributeValue(null, "volume")?.toIntOrNull()
                )
            }
            event = parser.next()
        }
        return null
    }
}
