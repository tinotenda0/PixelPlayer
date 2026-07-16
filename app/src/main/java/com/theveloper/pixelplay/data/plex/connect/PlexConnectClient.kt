package com.theveloper.pixelplay.data.plex.connect

import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.network.plex.PlexClientIdentity
import com.theveloper.pixelplay.data.plex.PlexRepository
import com.theveloper.pixelplay.data.plex.companion.PlexCompanionPlayerBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PixelPlayer Connect — the Spotify-Connect-style control plane.
 *
 * Keeps a WebSocket to the LAN session broker (the `connect/` sidecar running
 * next to the PMS). This device registers as both a player and a controller
 * under the active Plex user:
 *  - local Plex playback claims the user's session (queue + state pushed up),
 *  - transfers from other devices arrive as `adopt` and start local playback,
 *  - when another device is the active output, this phone mirrors the session
 *    and routes transport commands through the broker.
 */
@Singleton
class PlexConnectClient @Inject constructor(
    private val identity: PlexClientIdentity,
    private val repository: PlexRepository,
    private val bridge: PlexCompanionPlayerBridge,
    baseOkHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "PlexConnect"
        private const val BROKER_PORT = 32599
        private const val REPORT_TICK_MS = 1_500L
        private const val RECONNECT_MIN_MS = 5_000L
        private const val RECONNECT_MAX_MS = 60_000L
    }

    data class ConnectDevice(
        val id: String,
        val name: String,
        val platform: String,
        val product: String,
        val capabilities: List<String>,
        val isActive: Boolean,
        val volume: Int?
    )

    data class ConnectTrack(
        val ratingKey: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val thumb: String?
    )

    data class ConnectSession(
        val devices: List<ConnectDevice>,
        val activeDeviceId: String?,
        val queue: List<ConnectTrack>,
        val index: Int,
        val positionMs: Long,
        val positionAt: Long,
        val durationMs: Long,
        val state: String
    ) {
        val currentTrack: ConnectTrack? get() = queue.getOrNull(index)

        fun extrapolatedPositionMs(): Long =
            if (state == "playing") positionMs + (System.currentTimeMillis() - positionAt)
            else positionMs
    }

    private val httpClient: OkHttpClient = baseOkHttpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket: no read timeout
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    /** Short-timeout client for /health probes during broker discovery. */
    private val probeClient: OkHttpClient = baseOkHttpClient.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var lastGoodBrokerHost: String? = null

    private var scope: CoroutineScope? = null
    private var webSocket: WebSocket? = null

    @Volatile
    private var connectedToken: String? = null

    @Volatile
    private var lastClaimSignature: String? = null

    @Volatile
    private var reportedStopped = true

    private val _session = MutableStateFlow<ConnectSession?>(null)
    val session: StateFlow<ConnectSession?> = _session.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    val deviceId: String get() = identity.clientId

    /** True while another device is this user's active output. */
    val isRemoteActive: Boolean
        get() {
            val s = _session.value ?: return false
            return s.activeDeviceId != null && s.activeDeviceId != identity.clientId
        }

    /** Devices that can play, excluding this phone. */
    val remotePlayerDevices: List<ConnectDevice>
        get() = _session.value?.devices.orEmpty()
            .filter { it.id != identity.clientId && "player" in it.capabilities }

    // ─── Lifecycle ─────────────────────────────────────────────────────────

    @Synchronized
    fun restart() {
        stop()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        newScope.launch { connectionLoop() }
        newScope.launch { reportLoop() }
    }

    @Synchronized
    fun stop() {
        scope?.cancel()
        scope = null
        webSocket?.close(1000, "bye")
        webSocket = null
        connectedToken = null
        _isConnected.value = false
        _session.value = null
        lastClaimSignature = null
        reportedStopped = true
    }

    private suspend fun connectionLoop() {
        var backoff = RECONNECT_MIN_MS
        while (scope?.isActive == true) {
            val token = repository.activePlexTvToken
            if (token == null) {
                delay(30_000)
                continue
            }
            if (_isConnected.value && connectedToken == token) {
                delay(5_000)
                continue
            }

            val host = discoverBrokerHost(token)
            if (host == null) {
                Timber.tag(TAG).d("No broker found on any candidate host")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(RECONNECT_MAX_MS)
                continue
            }

            val url = "ws://$host:$BROKER_PORT/ws"
            Timber.tag(TAG).d("Connecting to broker $url")
            // newWebSocket is async — connectAndAwait suspends until the
            // handshake definitively succeeds or fails. Treating "call
            // created" as "connected" here once caused a hot loop that
            // spawned sockets until the app OOMed.
            val connected = connectAndAwait(url, token)
            if (connected) {
                lastGoodBrokerHost = host
                backoff = RECONNECT_MIN_MS
                // Hold until the socket drops before reconnecting.
                while (scope?.isActive == true && _isConnected.value && connectedToken == token) {
                    delay(2_000)
                }
                delay(1_000) // small settle so a flapping socket can't spin us
            } else {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(RECONNECT_MAX_MS)
            }
        }
    }

    /**
     * Find the host running the broker. The stored server URL may be a
     * tunnel/proxy hostname (Cloudflare etc.) that only forwards Plex's own
     * port — the broker next to the PMS is only reachable on the server's
     * direct LAN addresses, which plex.tv advertises in resources.
     */
    private suspend fun discoverBrokerHost(token: String): String? {
        val candidates = buildList {
            lastGoodBrokerHost?.let { add(it) }
            repository.serverUrl?.toHttpUrlOrNull()?.host?.let { add(it) }
            runCatching { repository.getServerLocalHosts() }
                .getOrDefault(emptyList())
                .forEach { add(it) }
        }.distinct().take(8)

        for (host in candidates) {
            if (probeBroker(host)) {
                if (host != lastGoodBrokerHost) {
                    Timber.tag(TAG).i("Broker found at $host (candidates: $candidates)")
                }
                return host
            }
        }
        return null
    }

    private suspend fun probeBroker(host: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("http://$host:$BROKER_PORT/health")
                    .get()
                    .build()
                probeClient.newCall(request).execute().use { it.isSuccessful }
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Opens the socket and suspends until onOpen or a failure (or timeout). */
    private suspend fun connectAndAwait(url: String, token: String): Boolean {
        webSocket?.cancel()
        val opened = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val ws: WebSocket
        try {
            val request = Request.Builder().url(url).build()
            ws = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(socket: WebSocket, response: okhttp3.Response) {
                    connectedToken = token
                    _isConnected.value = true
                    socket.send(helloMessage(token))
                    opened.complete(true)
                }

                override fun onMessage(socket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onFailure(socket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                    Timber.tag(TAG).d("Broker socket failure: ${t.message}")
                    // Only the current socket may clear shared state — a stale
                    // listener firing late must not clobber a fresh connection.
                    if (webSocket === socket) {
                        _isConnected.value = false
                        _session.value = null
                    }
                    opened.complete(false)
                }

                override fun onClosed(socket: WebSocket, code: Int, reason: String) {
                    if (webSocket === socket) {
                        _isConnected.value = false
                        _session.value = null
                    }
                    opened.complete(false)
                }
            })
            webSocket = ws
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not open broker socket")
            return false
        }

        val ok = kotlinx.coroutines.withTimeoutOrNull(10_000) { opened.await() } ?: false
        if (!ok) {
            ws.cancel()
            if (webSocket === ws) _isConnected.value = false
        }
        return ok
    }

    private fun helloMessage(token: String): String =
        JSONObject()
            .put("type", "hello")
            .put("token", token)
            .put(
                "device",
                JSONObject()
                    .put("id", identity.clientId)
                    .put("name", identity.deviceName)
                    .put("platform", "android")
                    .put("product", PlexClientIdentity.PRODUCT)
                    .put("capabilities", JSONArray(listOf("controller", "player")))
            )
            .toString()

    // ─── Incoming messages ─────────────────────────────────────────────────

    private fun handleMessage(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("type")) {
            "welcome" -> {
                Timber.tag(TAG).i("Connected to broker as ${msg.optJSONObject("user")?.optString("name") ?: "user"}")
                msg.optJSONObject("session")?.let { _session.value = parseSession(it) }
            }
            "session" -> msg.optJSONObject("session")?.let { _session.value = parseSession(it) }
            "adopt" -> scope?.launch { handleAdopt(msg) }
            "command" -> scope?.launch { handleCommand(msg) }
            "error" -> Timber.tag(TAG).w("Broker error: ${msg.optString("message")}")
        }
    }

    private fun parseSession(json: JSONObject): ConnectSession {
        val devices = json.optJSONArray("devices").toObjectList().map { d ->
            ConnectDevice(
                id = d.optString("id"),
                name = d.optString("name"),
                platform = d.optString("platform"),
                product = d.optString("product"),
                capabilities = d.optJSONArray("capabilities").toStringList(),
                isActive = d.optBoolean("isActive"),
                volume = if (d.has("volume") && !d.isNull("volume")) d.optInt("volume") else null
            )
        }
        return ConnectSession(
            devices = devices,
            activeDeviceId = json.optString("activeDeviceId").takeIf { it.isNotBlank() && it != "null" },
            queue = json.optJSONArray("queue").toObjectList().map { parseTrack(it) },
            index = json.optInt("index"),
            positionMs = json.optLong("positionMs"),
            positionAt = json.optLong("positionAt", System.currentTimeMillis()),
            durationMs = json.optLong("durationMs"),
            state = json.optString("state", "stopped")
        )
    }

    private fun parseTrack(json: JSONObject) = ConnectTrack(
        ratingKey = json.optString("ratingKey"),
        title = json.optString("title"),
        artist = json.optString("artist"),
        album = json.optString("album"),
        durationMs = json.optLong("durationMs"),
        thumb = json.optString("thumb").takeIf { it.isNotBlank() }
    )

    private suspend fun handleAdopt(msg: JSONObject) {
        val tracks = msg.optJSONArray("queue").toObjectList().map { parseTrack(it) }
        if (tracks.isEmpty()) return
        val index = msg.optInt("index", 0).coerceIn(0, tracks.size - 1)
        val positionMs = msg.optLong("positionMs", 0L)
        val autoplay = msg.optBoolean("autoplay", true)

        val songs = tracks.map { songForTrack(it) }
        Timber.tag(TAG).i("Adopting session: ${songs.size} tracks @ index $index")
        val started = bridge.playQueue(
            songs = songs,
            startIndex = index,
            offsetMs = positionMs,
            containerKey = null,
            playQueueId = null
        )
        if (started && !autoplay) bridge.pause()
        if (started) reportedStopped = false
    }

    private suspend fun songForTrack(track: ConnectTrack): Song {
        return repository.getSongByRatingKey(track.ratingKey) ?: Song(
            id = "plex_connect_${track.ratingKey}",
            title = track.title.ifBlank { "Unknown" },
            artist = track.artist.ifBlank { "Unknown" },
            artistId = -1L,
            album = track.album,
            albumId = -1L,
            path = "",
            contentUriString = "plex://${track.ratingKey}",
            albumArtUriString = "plex_cover://${track.ratingKey}",
            duration = track.durationMs,
            dateAdded = System.currentTimeMillis(),
            mimeType = null,
            bitrate = null,
            sampleRate = null,
            plexId = track.ratingKey
        )
    }

    private suspend fun handleCommand(msg: JSONObject) {
        when (msg.optString("action")) {
            "play" -> bridge.play()
            "pause" -> bridge.pause()
            "stop" -> bridge.pause() // another device took the session; just go quiet
            "seekTo" -> bridge.seekTo(msg.optLong("positionMs"))
            "setVolume" -> bridge.setVolume(msg.optInt("volume", 100))
            "next" -> bridge.skipNext()
            "previous" -> bridge.skipPrevious()
            "playIndex" -> bridge.playIndex(msg.optInt("index"))
        }
    }

    // ─── Reporting (this device as the player) ─────────────────────────────

    private suspend fun reportLoop() {
        while (scope?.isActive == true) {
            delay(REPORT_TICK_MS)
            if (!_isConnected.value) continue
            runCatching { reportOnce() }
                .onFailure { Timber.tag(TAG).d(it, "report tick failed") }
        }
    }

    private suspend fun reportOnce() {
        val ws = webSocket ?: return
        val state = bridge.currentState()
        val selfActive = _session.value?.activeDeviceId == identity.clientId

        if (state.state == "playing" && state.ratingKey != null) {
            val tracks = bridge.currentQueueTracks()
            val signature = tracks.joinToString(",") { it.ratingKey }
            if (!selfActive || signature != lastClaimSignature) {
                // Claim (or re-claim after queue change) the user's session.
                lastClaimSignature = signature
                val queueJson = JSONArray()
                tracks.forEach { t ->
                    queueJson.put(
                        JSONObject()
                            .put("ratingKey", t.ratingKey)
                            .put("title", t.title)
                            .put("artist", t.artist)
                            .put("album", t.album)
                            .put("durationMs", t.durationMs)
                            .putOpt("thumb", repository.getThumbPathForRatingKey(t.ratingKey))
                    )
                }
                ws.send(
                    JSONObject()
                        .put("type", "queue")
                        .put("tracks", queueJson)
                        .put("index", state.index)
                        .put("positionMs", state.timeMs)
                        .put("state", "playing")
                        .put("durationMs", state.durationMs)
                        .toString()
                )
                reportedStopped = false
                return
            }
        }

        if (!selfActive) return

        if (state.state == "stopped" || state.ratingKey == null) {
            if (!reportedStopped) {
                reportedStopped = true
                sendState(ws, "stopped", state)
            }
            return
        }

        reportedStopped = false
        sendState(ws, state.state, state)
    }

    private fun sendState(ws: WebSocket, stateName: String, state: com.theveloper.pixelplay.data.plex.companion.CompanionPlaybackState) {
        ws.send(
            JSONObject()
                .put("type", "state")
                .put("state", stateName)
                .put("positionMs", state.timeMs)
                .put("durationMs", state.durationMs)
                .put("index", state.index)
                .putOpt("ratingKey", state.ratingKey)
                .toString()
        )
    }

    // ─── Controller surface (phone controlling a remote device) ───────────

    private fun sendCommand(action: String, build: JSONObject.() -> Unit = {}) {
        val ws = webSocket ?: return
        ws.send(JSONObject().put("type", "command").put("action", action).apply(build).toString())
    }

    fun playPause() {
        val playing = _session.value?.state == "playing"
        sendCommand(if (playing) "pause" else "play")
        _session.value = _session.value?.copy(state = if (playing) "paused" else "playing")
    }

    fun next() = sendCommand("next")

    fun previous() = sendCommand("previous")

    fun seekTo(positionMs: Long) {
        _session.value = _session.value?.copy(
            positionMs = positionMs.coerceAtLeast(0L),
            positionAt = System.currentTimeMillis()
        )
        sendCommand("seekTo") { put("positionMs", positionMs.coerceAtLeast(0L)) }
    }

    fun setRemoteVolume(volume: Int) = sendCommand("setVolume") { put("volume", volume.coerceIn(0, 100)) }

    fun playIndexRemote(index: Int) = sendCommand("playIndex") { put("index", index) }

    /** Move the running session to another device (or back to this phone). */
    fun transfer(targetDeviceId: String) = sendCommand("transfer") { put("targetDeviceId", targetDeviceId) }

    /** Start fresh content on the remote active output (song picks while connected). */
    suspend fun playQueueRemote(songs: List<Song>, startSong: Song, startPositionMs: Long = 0L): Boolean {
        val plexSongs = songs.filter { !it.plexId.isNullOrBlank() }
        val startKey = startSong.plexId
        if (plexSongs.isEmpty() || startKey.isNullOrBlank()) return false

        val tracksJson = JSONArray()
        plexSongs.forEach { song ->
            tracksJson.put(
                JSONObject()
                    .put("ratingKey", song.plexId)
                    .put("title", song.title)
                    .put("artist", song.displayArtist)
                    .put("album", song.album)
                    .put("durationMs", song.duration)
                    .putOpt("thumb", repository.getThumbPathForRatingKey(song.plexId!!))
            )
        }
        val index = plexSongs.indexOfFirst { it.plexId == startKey }.coerceAtLeast(0)
        sendCommand("playQueue") {
            put("tracks", tracksJson)
            put("index", index)
            put("positionMs", startPositionMs.coerceAtLeast(0L))
        }
        // Optimistic: the broker will broadcast the authoritative session.
        _session.value = _session.value?.copy(state = "playing", index = index)
        return true
    }

    fun songForCurrentTrack(): ConnectTrack? = _session.value?.currentTrack

    suspend fun resolveSongForTrack(track: ConnectTrack): Song = songForTrack(track)

    // ─── JSON helpers ──────────────────────────────────────────────────────

    private fun JSONArray?.toObjectList(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
    }
}
