package com.theveloper.pixelplay.data.plex.companion

import com.theveloper.pixelplay.data.network.plex.PlexApiService
import com.theveloper.pixelplay.data.network.plex.PlexClientIdentity
import com.theveloper.pixelplay.data.plex.PlexRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.media3.common.Player
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import timber.log.Timber
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Makes this install a Plex Companion *target*: the reverse of
 * [com.theveloper.pixelplay.data.network.plex.PlexCompanionClient]. Plexamp
 * (and Plex Web / official apps) can discover PixelPlayer as a player and
 * remote-control playback on this phone.
 *
 * Discovery is advertised three ways, matching what real players do:
 *  1. plex.tv — our device record carries provides=player (header on every
 *     plex.tv call) and we publish reachable Connection URIs for it here.
 *  2. GDM — [PlexGdmResponder] answers LAN multicast searches.
 *  3. PMS proxy — a long-poll against /player/proxy/poll registers us with
 *     the server and relays commands even when direct HTTP isn't possible.
 */
@Singleton
class PlexCompanionTarget @Inject constructor(
    private val identity: PlexClientIdentity,
    private val api: PlexApiService,
    private val repository: PlexRepository,
    private val bridge: PlexCompanionPlayerBridge,
    private val gdm: PlexGdmResponder,
    baseOkHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "PlexCompanionTarget"

        // 32500 is the conventional Companion port (Plexamp); if it's taken
        // (e.g. Plexamp installed on this phone) we walk forward and advertise
        // whatever we actually bound.
        private val PORT_CANDIDATES = 32500..32510

        private const val TIMELINE_TICK_MS = 1_000L
        private const val IDLE_TICK_MS = 10_000L
        private const val LONG_POLL_MAX_MS = 15_000L
        private const val SUBSCRIBER_MAX_FAILURES = 3

        private const val CONTROLLABLE_MUSIC =
            "playPause,stop,volume,shuffle,repeat,seekTo,skipPrevious,skipNext,stepBack,stepForward"
        private const val CONTROLLABLE_VIDEO =
            "playPause,stop,volume,seekTo,skipPrevious,skipNext,stepBack,stepForward"
        private const val CONTROLLABLE_PHOTO = "playPause,stop,skipPrevious,skipNext"

        private const val OK_RESPONSE = """<Response code="200" status="OK"/>"""
    }

    private data class Subscriber(
        /** Full timeline endpoint, e.g. http://ip:port/:/timeline or {pms}/player/proxy/timeline */
        val url: String,
        @Volatile var commandId: Int,
        /** Extra query params (e.g. X-Plex-Token for PMS-proxied subscribers). */
        val extraParams: String? = null,
        @Volatile var failures: Int = 0
    )

    private val httpClient: OkHttpClient = baseOkHttpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var scope: CoroutineScope? = null
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @Volatile
    private var boundPort: Int = 0

    /** Subscribed controllers, keyed by their client identifier. */
    private val subscribers = ConcurrentHashMap<String, Subscriber>()

    // Bumped whenever the reported playback state changes; long-polls wait on it.
    private val stateVersion = MutableStateFlow(0L)

    @Volatile
    private var lastState: CompanionPlaybackState? = null

    val isRunning: Boolean
        get() = scope != null

    @Synchronized
    fun start() {
        if (isRunning) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        newScope.launch {
            val port = bindServer(newScope) ?: run {
                Timber.tag(TAG).e("No Companion port available; target disabled")
                return@launch
            }
            boundPort = port
            Timber.tag(TAG).i("Companion target listening on $port as ${identity.deviceName}")
            gdm.start(newScope, port)
            publishConnections(port)
            newScope.launch { timelineLoop() }
            newScope.launch { pmsProxyLoop() }
        }
    }

    @Synchronized
    fun stop() {
        gdm.stop()
        scope?.cancel()
        scope = null
        server?.stop(500, 1000)
        server = null
        boundPort = 0
        subscribers.clear()
        bridge.release()
        Timber.tag(TAG).i("Companion target stopped")
    }

    // ─── HTTP server ─────────────────────────────────────────────────────

    private fun bindServer(owningScope: CoroutineScope): Int? {
        for (port in PORT_CANDIDATES) {
            val free = runCatching { ServerSocket(port).use { true } }.getOrDefault(false)
            if (!free) continue
            return try {
                val created = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                    routing {
                        options("/{path...}") { respondCors() }
                        get("/{path...}") { handleRequest() }
                    }
                }
                created.start(wait = false)
                // Publish under the same lock stop() uses: if stop() ran while
                // we were binding, this run's scope is dead — shut the fresh
                // server down instead of leaking it listening forever.
                val published = synchronized(this) {
                    if (scope === owningScope) {
                        server = created
                        true
                    } else {
                        false
                    }
                }
                if (!published) {
                    Timber.tag(TAG).i("Target stopped during bind; discarding server on $port")
                    created.stop(200, 500)
                    return null
                }
                port
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to bind Companion server on $port")
                null
            }
        }
        return null
    }

    private suspend fun io.ktor.server.application.ApplicationCall.applyPlexHeaders() {
        response.header("X-Plex-Client-Identifier", identity.clientId)
        response.header("X-Plex-Product", PlexClientIdentity.PRODUCT)
        response.header("X-Plex-Version", PlexClientIdentity.VERSION)
        response.header("X-Plex-Device-Name", identity.deviceName)
        response.header("X-Plex-Platform", PlexClientIdentity.PLATFORM)
        response.header("Access-Control-Allow-Origin", "*")
    }

    private suspend fun io.ktor.server.routing.RoutingContext.respondCors() {
        call.applyPlexHeaders()
        call.response.header("Access-Control-Max-Age", "1209600")
        call.response.header(
            "Access-Control-Allow-Methods", "POST, GET, OPTIONS, DELETE, PUT, HEAD"
        )
        call.response.header(
            "Access-Control-Allow-Headers",
            "x-plex-version, x-plex-platform-version, x-plex-username, " +
                "x-plex-client-identifier, x-plex-target-client-identifier, " +
                "x-plex-device-name, x-plex-platform, x-plex-product, accept, " +
                "x-plex-device, x-plex-device-screen-resolution, x-plex-token"
        )
        call.respondText("", ContentType.Text.Plain, HttpStatusCode.OK)
    }

    private suspend fun io.ktor.server.routing.RoutingContext.handleRequest() {
        val path = call.request.path().trimStart('/')
        val params = call.request.queryParameters
        call.applyPlexHeaders()

        // Commands addressed to a different player are not ours to answer.
        val target = call.request.header("X-Plex-Target-Client-Identifier")
        if (!target.isNullOrBlank() && target != identity.clientId) {
            call.respondText("Wrong target", ContentType.Text.Plain, HttpStatusCode.NotFound)
            return
        }

        when {
            path == "resources" -> {
                call.respondText(resourcesXml(), ContentType.Text.Xml, HttpStatusCode.OK)
            }

            path == "player/timeline/poll" -> {
                val commandId = params["commandID"]?.toIntOrNull() ?: 0
                if (params["wait"] == "1") {
                    val version = stateVersion.value
                    withTimeoutOrNull(LONG_POLL_MAX_MS) {
                        stateVersion.first { it != version }
                    }
                }
                val state = bridge.currentState().also { lastState = it }
                call.respondText(
                    timelineXml(state, commandId),
                    ContentType.Text.Xml,
                    HttpStatusCode.OK
                )
            }

            path == "player/timeline/subscribe" -> {
                val uuid = call.request.header("X-Plex-Client-Identifier")
                val protocol = params["protocol"] ?: "http"
                val port = params["port"]
                if (uuid.isNullOrBlank() || port.isNullOrBlank()) {
                    call.respondText("Missing subscription params", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                    return
                }
                val host = call.request.origin.remoteHost
                val commandId = params["commandID"]?.toIntOrNull() ?: 0
                subscribers[uuid] = Subscriber("$protocol://$host:$port/:/timeline", commandId)
                Timber.tag(TAG).d("Subscriber added: $uuid -> $protocol://$host:$port")
                // Immediate first timeline so the controller paints right away.
                scope?.launch { pushTimelines(force = true) }
                call.respondText(OK_RESPONSE, ContentType.Text.Xml, HttpStatusCode.OK)
            }

            path == "player/timeline/unsubscribe" -> {
                call.request.header("X-Plex-Client-Identifier")?.let { subscribers.remove(it) }
                call.respondText(OK_RESPONSE, ContentType.Text.Xml, HttpStatusCode.OK)
            }

            path.startsWith("player/") -> {
                val uuid = call.request.header("X-Plex-Client-Identifier")
                if (uuid != null) {
                    subscribers[uuid]?.commandId =
                        params["commandID"]?.toIntOrNull() ?: subscribers[uuid]?.commandId ?: 0
                }
                val paramMap = buildMap {
                    params.entries().forEach { (key, values) -> values.firstOrNull()?.let { put(key, it) } }
                }
                val handled = handleCommand(path, paramMap)
                if (handled) {
                    call.respondText(OK_RESPONSE, ContentType.Text.Xml, HttpStatusCode.OK)
                } else {
                    call.respondText("Unknown path: $path", ContentType.Text.Plain, HttpStatusCode.NotFound)
                }
            }

            else -> {
                call.respondText("Not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
            }
        }
    }

    // ─── Command processing (shared by HTTP server and PMS proxy) ────────

    private suspend fun handleCommand(path: String, params: Map<String, String>): Boolean {
        Timber.tag(TAG).d("Command: $path $params")
        val ok = when (path.removePrefix("player/").removePrefix("/")) {
            "playback/playMedia" -> handlePlayMedia(params)
            "playback/play" -> bridge.play()
            "playback/pause" -> bridge.pause()
            "playback/stop" -> bridge.stop()
            "playback/skipNext" -> bridge.skipNext()
            "playback/skipPrevious" -> bridge.skipPrevious()
            "playback/seekTo" -> bridge.seekTo(params["offset"]?.toLongOrNull() ?: 0L)
            "playback/setParameters" -> {
                params["volume"]?.toIntOrNull()?.let { bridge.setVolume(it) }
                true
            }
            "playback/refreshPlayQueue" -> true
            "playback/skipTo" -> true
            // We can't navigate an app UI remotely; acknowledge so controllers
            // don't treat us as broken.
            else -> path.startsWith("player/navigation/")
        }
        if (ok) bumpState()
        return ok
    }

    private suspend fun handlePlayMedia(params: Map<String, String>): Boolean {
        val containerKey = params["containerKey"]
        val key = params["key"]
        val offsetMs = params["offset"]?.toLongOrNull() ?: 0L

        val playQueueId = containerKey
            ?.substringAfter("/playQueues/", "")
            ?.takeWhile { it.isDigit() }
            ?.toLongOrNull()

        val songs = when {
            playQueueId != null -> repository.getPlayQueueSongs(playQueueId)
            else -> emptyList()
        }.ifEmpty {
            // No queue (or fetch failed): fall back to the single addressed track.
            val ratingKey = key?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ratingKey?.let { rk -> repository.getSongByRatingKey(rk)?.let { listOf(it) } } ?: emptyList()
        }

        if (songs.isEmpty()) {
            Timber.tag(TAG).w("playMedia: could not resolve any songs ($params)")
            return false
        }

        val startRatingKey = key?.substringAfterLast('/')
        val startIndex = songs.indexOfFirst { it.plexId == startRatingKey }.coerceAtLeast(0)
        return bridge.playQueue(
            songs = songs,
            startIndex = startIndex,
            offsetMs = offsetMs,
            containerKey = containerKey,
            playQueueId = playQueueId
        )
    }

    // ─── Timelines ────────────────────────────────────────────────────────

    private suspend fun timelineLoop() {
        while (scope?.isActive == true) {
            // Polling the bridge binds a MediaController (starting MusicService)
            // and hops to the main thread — never do that once a second while
            // idle. Only poll when a controller is subscribed or audio plays.
            val playbackActive =
                com.theveloper.pixelplay.data.service.PlaybackActivityTracker.isPlaybackActive
            if (subscribers.isEmpty() && !playbackActive) {
                delay(IDLE_TICK_MS)
                continue
            }
            pushTimelines(force = false)
            delay(TIMELINE_TICK_MS)
        }
    }

    private fun bumpState() {
        stateVersion.value = stateVersion.value + 1
    }

    private suspend fun pushTimelines(force: Boolean) {
        val state = bridge.currentState()
        val previous = lastState
        lastState = state
        val changed = previous == null ||
            previous.state != state.state ||
            previous.ratingKey != state.ratingKey ||
            previous.volume != state.volume
        if (changed) bumpState()

        if (subscribers.isEmpty()) return
        if (!force && !changed && state.state != "playing") return

        val overallState = state.state
        subscribers.forEach { (uuid, subscriber) ->
            val commandId = subscriber.commandId + 1
            val xml = timelineXml(state, commandId)
            val url = buildString {
                append(subscriber.url)
                append("?commandID=").append(commandId)
                append("&state=").append(overallState)
                subscriber.extraParams?.let { append('&').append(it) }
            }
            val delivered = postXml(url, xml)
            if (delivered) {
                subscriber.failures = 0
            } else if (++subscriber.failures >= SUBSCRIBER_MAX_FAILURES) {
                Timber.tag(TAG).d("Dropping unreachable subscriber $uuid")
                subscribers.remove(uuid)
            }
        }
    }

    private fun postXml(url: String, xml: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("X-Plex-Client-Identifier", identity.clientId)
                .header("X-Plex-Product", PlexClientIdentity.PRODUCT)
                .header("X-Plex-Version", PlexClientIdentity.VERSION)
                .header("X-Plex-Device-Name", identity.deviceName)
                .header("X-Plex-Platform", PlexClientIdentity.PLATFORM)
                .post(xml.toRequestBody("text/xml;charset=utf-8".toMediaType()))
                .build()
            httpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private fun resourcesXml(): String = buildString {
        append("""<MediaContainer size="1">""")
        append("<Player")
        attr("machineIdentifier", identity.clientId)
        attr("title", identity.deviceName)
        attr("product", PlexClientIdentity.PRODUCT)
        attr("version", PlexClientIdentity.VERSION)
        attr("platform", PlexClientIdentity.PLATFORM)
        attr("platformVersion", identity.platformVersion)
        attr("deviceClass", PlexClientIdentity.DEVICE_CLASS)
        attr("protocolVersion", PlexClientIdentity.PROTOCOL_VERSION)
        attr("protocolCapabilities", PlexClientIdentity.PROTOCOL_CAPABILITIES)
        append("/></MediaContainer>")
    }

    private suspend fun timelineXml(state: CompanionPlaybackState, commandId: Int): String {
        val serverUrl = repository.serverUrl?.toHttpUrlOrNull()
        val machineId = if (state.ratingKey != null) repository.serverMachineId() else null
        return buildString {
            append("""<MediaContainer location="navigation" commandID="$commandId">""")

            // Music — the only timeline this player actually drives.
            append("<Timeline")
            attr("controllable", CONTROLLABLE_MUSIC)
            attr("type", "music")
            attr("itemType", "music")
            attr("state", state.state)
            attr("time", state.timeMs.toString())
            attr("duration", state.durationMs.toString())
            attr("seekRange", "0-${state.durationMs}")
            attr("volume", state.volume.toString())
            attr("mute", if (state.volume == 0) "1" else "0")
            attr("shuffle", if (state.shuffle) "1" else "0")
            attr(
                "repeat",
                when (state.repeatMode) {
                    Player.REPEAT_MODE_ONE -> "1"
                    Player.REPEAT_MODE_ALL -> "2"
                    else -> "0"
                }
            )
            attr("mediaIndex", "0")
            attr("partIndex", "0")
            attr("partCount", "1")
            attr("providerIdentifier", "com.plexapp.plugins.library")
            state.ratingKey?.let { rk ->
                attr("key", "/library/metadata/$rk")
                attr("ratingKey", rk)
                machineId?.let { attr("machineIdentifier", it) }
                serverUrl?.let {
                    attr("protocol", it.scheme)
                    attr("address", it.host)
                    attr("port", it.port.toString())
                }
                state.containerKey?.let { attr("containerKey", it) }
                state.playQueueId?.let {
                    attr("playQueueID", it.toString())
                    attr("playQueueVersion", "1")
                }
            }
            append("/>")

            append("""<Timeline controllable="$CONTROLLABLE_VIDEO" type="video" state="stopped"/>""")
            append("""<Timeline controllable="$CONTROLLABLE_PHOTO" type="photo" state="stopped"/>""")
            append("</MediaContainer>")
        }
    }

    private fun StringBuilder.attr(name: String, value: String) {
        append(' ').append(name).append("=\"").append(escapeXml(value)).append('"')
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    // ─── plex.tv connection publishing ────────────────────────────────────

    private suspend fun publishConnections(port: Int) {
        val token = repository.activePlexTvToken ?: return
        val uris = localIpv4Addresses().map { "http://$it:$port" }
        if (uris.isEmpty()) {
            Timber.tag(TAG).w("No local addresses to publish")
            return
        }
        api.publishCompanionConnections(token, uris)
            .onSuccess { Timber.tag(TAG).i("Published Companion connections: $uris") }
            .onFailure { Timber.tag(TAG).w(it, "Publishing Companion connections failed") }
    }

    private fun localIpv4Addresses(): List<String> {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { it.hostAddress ?: "" }
                .filter { it.isNotBlank() }
                .toList()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not enumerate local addresses")
            emptyList()
        }
    }

    // ─── PMS Companion proxy (relay) ───────────────────────────────────────

    /**
     * Long-poll the active PMS for relayed Companion commands. Listening is
     * itself the registration: the server starts listing us as a controllable
     * client and forwards commands from controllers that can't reach us
     * directly.
     */
    private suspend fun pmsProxyLoop() {
        var backoff = 1L
        while (scope?.isActive == true) {
            val serverUrl = repository.serverUrl
            val token = repository.activeServerToken
            if (serverUrl == null || token == null) {
                delay(30_000)
                continue
            }

            val url = serverUrl.trimEnd('/') +
                "/player/proxy/poll?timeout=1" +
                "&deviceClass=${PlexClientIdentity.DEVICE_CLASS}" +
                "&protocolVersion=${PlexClientIdentity.PROTOCOL_VERSION}" +
                "&protocolCapabilities=${PlexClientIdentity.PROTOCOL_CAPABILITIES}" +
                "&X-Plex-Token=$token"

            val body = try {
                val request = Request.Builder()
                    .url(url)
                    .header("X-Plex-Client-Identifier", identity.clientId)
                    .header("X-Plex-Product", PlexClientIdentity.PRODUCT)
                    .header("X-Plex-Version", PlexClientIdentity.VERSION)
                    .header("X-Plex-Device-Name", identity.deviceName)
                    .header("X-Plex-Platform", PlexClientIdentity.PLATFORM)
                    .header("X-Plex-Provides", PlexClientIdentity.PROVIDES)
                    .get()
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.code == 401 -> {
                            Timber.tag(TAG).w("PMS proxy poll unauthorized; retrying later")
                            delay(TimeUnit.MINUTES.toMillis(10))
                            null
                        }
                        !response.isSuccessful -> {
                            delay(TimeUnit.SECONDS.toMillis(backoff))
                            backoff = (backoff * 2).coerceAtMost(128)
                            null
                        }
                        else -> {
                            backoff = 1
                            response.body.string()
                        }
                    }
                }
            } catch (_: java.io.InterruptedIOException) {
                // Expected: the PMS holds the connection ~20s when idle.
                continue
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                delay(TimeUnit.SECONDS.toMillis(backoff))
                backoff = (backoff * 2).coerceAtMost(128)
                continue
            }

            if (body.isNullOrBlank()) continue

            val command = parseProxyCommand(body) ?: continue
            val (path, params, commandId, controllerUuid) = command

            // Controllers commanding us through the server implicitly expect
            // timeline pushes back through it as well.
            if (controllerUuid != null && !subscribers.containsKey(controllerUuid)) {
                subscribers[controllerUuid] = Subscriber(
                    url = serverUrl.trimEnd('/') + "/player/proxy/timeline",
                    commandId = commandId?.toIntOrNull() ?: 0,
                    extraParams = "X-Plex-Token=$token"
                )
            }

            val handled = handleCommand(path.trimStart('/'), params)
            if (handled && commandId != null) {
                val respondUrl = serverUrl.trimEnd('/') +
                    "/player/proxy/response?commandID=$commandId&X-Plex-Token=$token"
                postXml(respondUrl, OK_RESPONSE)
            }
        }
    }

    private data class ProxyCommand(
        val path: String,
        val params: Map<String, String>,
        val commandId: String?,
        val controllerUuid: String?
    )

    /** The PMS relays one Command element per poll response. */
    private fun parseProxyCommand(xml: String): ProxyCommand? {
        return try {
            val parser = android.util.Xml.newPullParser()
            parser.setInput(xml.reader())
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "Command") {
                    val attrs = (0 until parser.attributeCount).associate {
                        parser.getAttributeName(it) to parser.getAttributeValue(it)
                    }
                    val path = attrs["path"] ?: return null
                    // query* attributes are the original request's query params.
                    val params = attrs
                        .filterKeys { it.startsWith("query") && it.length > 5 }
                        .mapKeys { (key, _) -> key[5].lowercaseChar() + key.substring(6) }
                    return ProxyCommand(
                        path = path,
                        params = params,
                        commandId = attrs["commandID"],
                        controllerUuid = attrs["clientIdentifier"]
                    )
                }
                event = parser.next()
            }
            null
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not parse proxy command")
            null
        }
    }
}
