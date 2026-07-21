package com.theveloper.pixelplay.data.network.roku

import android.content.Context
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A Roku device on the LAN and its relevant state, discovered over SSDP + ECP.
 */
data class RokuDevice(
    val name: String,
    val host: String,          // LAN IP, e.g. 192.168.3.42
    val ecpPort: Int = 8060,
    val hasPlex: Boolean
) {
    val ecpBase: String get() = "http://$host:$ecpPort"
}

/**
 * Roku External Control Protocol (ECP) client — the HTTP API every Roku
 * exposes on port 8060. Roku does not speak Google Cast, so casting to a Roku
 * TV means: discover it here, launch the Plex channel on it, and then drive
 * playback through Plex Companion (which the Roku Plex app implements).
 *
 * ECP reference: https://developer.roku.com/docs/developer-program/dev-tools/external-control-api.md
 */
@Singleton
class RokuEcpClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
    baseOkHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "RokuEcp"
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SSDP_SEARCH_TARGET = "roku:ecp"
        private const val DISCOVERY_TIMEOUT_MS = 3_000
        /** Plex channel id in the Roku channel store. */
        const val PLEX_CHANNEL_ID = "13535"

        private val M_SEARCH = (
            "M-SEARCH * HTTP/1.1\r\n" +
                "Host: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                "Man: \"ssdp:discover\"\r\n" +
                "ST: $SSDP_SEARCH_TARGET\r\n" +
                "MX: 2\r\n\r\n"
            ).toByteArray()
    }

    private val http: OkHttpClient = baseOkHttpClient.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * Discover Rokus on the LAN and resolve each one's name + whether the Plex
     * channel is installed. Blocking for up to ~[DISCOVERY_TIMEOUT_MS] + probes.
     */
    suspend fun discover(): List<RokuDevice> = withContext(Dispatchers.IO) {
        val hosts = ssdpSearch()
        hosts.mapNotNull { host ->
            runCatching { describe(host) }.getOrNull()
        }.sortedBy { it.name.lowercase() }
    }

    private fun ssdpSearch(): Set<String> {
        val hosts = linkedSetOf<String>()
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("PixelPlayerRokuSSDP")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 500
            }
            val target = InetSocketAddress(InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT)
            // Send a few times — UDP is lossy and Rokus can be slow to answer.
            repeat(2) {
                runCatching { socket.send(DatagramPacket(M_SEARCH, M_SEARCH.size, target)) }
            }

            val buffer = ByteArray(2048)
            val deadline = System.currentTimeMillis() + DISCOVERY_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                }
                val response = String(packet.data, 0, packet.length)
                if (!response.contains("roku", ignoreCase = true)) continue
                extractLocationHost(response)?.let { hosts.add(it) }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "SSDP search failed")
        } finally {
            runCatching { socket?.close() }
            lock?.let { runCatching { if (it.isHeld) it.release() } }
        }
        Timber.tag(TAG).d("SSDP found Roku hosts: %s", hosts)
        return hosts
    }

    /** Pull the host out of a "LOCATION: http://192.168.x.y:8060/" SSDP header. */
    private fun extractLocationHost(response: String): String? {
        val location = response.lineSequence()
            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?: return null
        return runCatching { java.net.URI(location).host }.getOrNull()
    }

    private fun describe(host: String): RokuDevice? {
        val info = getText("http://$host:8060/query/device-info") ?: return null
        val name = firstXmlValue(info, "user-device-name")
            ?: firstXmlValue(info, "friendly-device-name")
            ?: firstXmlValue(info, "default-device-name")
            ?: firstXmlValue(info, "model-name")
            ?: "Roku"
        // Detect the Plex channel generously: by the store id OR by name (ids
        // can differ by region/build), and if the apps query fails entirely,
        // assume installed — the launch attempt is the real test, and a false
        // "install Plex" refusal on a TV that has it is far worse.
        val apps = getText("http://$host:8060/query/apps")
        val hasPlex = when {
            apps == null -> true
            apps.contains("id=\"$PLEX_CHANNEL_ID\"") -> true
            else -> Regex("<app[^>]*>[^<]*plex[^<]*</app>", RegexOption.IGNORE_CASE)
                .containsMatchIn(apps)
        }
        return RokuDevice(name = name.trim(), host = host, hasPlex = hasPlex)
    }

    /** Launch the Plex channel on the Roku so it starts advertising Companion. */
    suspend fun launchPlex(device: RokuDevice): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${device.ecpBase}/launch/$PLEX_CHANNEL_ID")
                .post(ByteArray(0).toRequestBody(null))
                .build()
            http.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "launchPlex failed for ${device.host}")
            false
        }
    }

    private fun getText(url: String): String? {
        return try {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body.string() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun firstXmlValue(xml: String, tag: String): String? {
        val open = "<$tag>"
        val close = "</$tag>"
        val start = xml.indexOf(open)
        if (start < 0) return null
        val end = xml.indexOf(close, start + open.length)
        if (end < 0) return null
        return xml.substring(start + open.length, end)
            .replace("&amp;", "&")
            .takeIf { it.isNotBlank() }
    }
}
