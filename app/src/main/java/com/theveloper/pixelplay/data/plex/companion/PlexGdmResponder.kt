package com.theveloper.pixelplay.data.plex.companion

import android.content.Context
import android.net.wifi.WifiManager
import com.theveloper.pixelplay.data.network.plex.PlexClientIdentity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plex GDM ("G'Day Mate") responder — the LAN half of Companion discovery.
 *
 * Controllers multicast `M-SEARCH * HTTP/1.0` to 239.0.0.250:32412 to find
 * players; the PMS listens on 239.0.0.250:32413 for player HELLO/BYE
 * registrations. Answering both makes this install show up as a local
 * Companion player without any cloud round-trip.
 */
@Singleton
class PlexGdmResponder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val identity: PlexClientIdentity
) {
    companion object {
        private const val TAG = "PlexGDM"
        private const val MULTICAST_ADDRESS = "239.0.0.250"
        private const val CLIENT_SEARCH_PORT = 32412
        private const val PMS_REGISTER_PORT = 32413
    }

    private var socket: MulticastSocket? = null
    private var listenJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private fun clientDescriptor(companionPort: Int): String =
        "Content-Type: plex/media-player\n" +
            "Resource-Identifier: ${identity.clientId}\n" +
            "Name: ${identity.deviceName}\n" +
            "Port: $companionPort\n" +
            "Product: ${PlexClientIdentity.PRODUCT}\n" +
            "Version: ${PlexClientIdentity.VERSION}\n" +
            "Protocol: plex\n" +
            "Protocol-Version: ${PlexClientIdentity.PROTOCOL_VERSION}\n" +
            "Protocol-Capabilities: ${PlexClientIdentity.PROTOCOL_CAPABILITIES}\n" +
            "Device-Class: ${PlexClientIdentity.DEVICE_CLASS}\n"

    fun start(scope: CoroutineScope, companionPort: Int) {
        if (listenJob?.isActive == true) return
        listenJob = scope.launch(Dispatchers.IO) {
            // Each run OWNS its socket and lock as locals. The shared fields
            // exist only so stop() can close the live socket to interrupt a
            // blocking receive(); the finally below cleans up its OWN
            // resources and compare-and-clears the fields — an old run
            // draining after a quick stop()/start() cycle must never close
            // the replacement's socket or release its multicast lock.
            var localLock: WifiManager.MulticastLock? = null
            var localSock: MulticastSocket? = null
            try {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                localLock = wifi?.createMulticastLock("PixelPlayerPlexGDM")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
                multicastLock = localLock

                val sock = MulticastSocket(CLIENT_SEARCH_PORT).apply {
                    reuseAddress = true
                    joinGroup(
                        InetSocketAddress(InetAddress.getByName(MULTICAST_ADDRESS), CLIENT_SEARCH_PORT),
                        null
                    )
                    // Wake sparingly; discovery latency of a few seconds is fine
                    // and this loop otherwise ticks the CPU all day.
                    soTimeout = 5000
                }
                localSock = sock
                socket = sock

                val descriptor = clientDescriptor(companionPort)
                sendTo(sock, "HELLO * HTTP/1.0\n$descriptor", PMS_REGISTER_PORT)
                Timber.tag(TAG).i("GDM responder listening on $CLIENT_SEARCH_PORT")

                val buffer = ByteArray(1024)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        sock.receive(packet)
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    }
                    val data = String(packet.data, 0, packet.length)
                    if (data.contains("M-SEARCH * HTTP/1.")) {
                        val reply = "HTTP/1.0 200 OK\n$descriptor".toByteArray()
                        runCatching {
                            sock.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Timber.tag(TAG).w(e, "GDM responder stopped unexpectedly")
                }
            } finally {
                localSock?.let { sock ->
                    runCatching {
                        sendTo(sock, "BYE * HTTP/1.0\n${clientDescriptor(companionPort)}", PMS_REGISTER_PORT)
                    }
                    runCatching { sock.close() }
                }
                localLock?.let { runCatching { if (it.isHeld) it.release() } }
                // Only clear the shared fields if they still refer to THIS
                // run's resources — a newer run may have replaced them.
                synchronized(this@PlexGdmResponder) {
                    if (socket === localSock) socket = null
                    if (multicastLock === localLock) multicastLock = null
                }
            }
        }
    }

    fun stop() {
        listenJob?.cancel()
        listenJob = null
        // Close the live socket so a blocking receive() unblocks immediately
        // instead of draining out its full soTimeout.
        synchronized(this) {
            socket?.let { runCatching { it.close() } }
        }
    }

    private fun sendTo(sock: DatagramSocket, message: String, port: Int) {
        runCatching {
            val bytes = message.toByteArray()
            sock.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(MULTICAST_ADDRESS), port))
        }.onFailure { Timber.tag(TAG).w(it, "GDM send failed") }
    }
}
