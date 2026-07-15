package com.theveloper.pixelplay.data.network.plex

import com.theveloper.pixelplay.data.plex.model.PlexCredentials
import com.theveloper.pixelplay.data.plex.model.PlexHomeUser
import com.theveloper.pixelplay.data.plex.model.PlexServerConnection
import com.theveloper.pixelplay.data.plex.model.PlexServerResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plex Media Server API client.
 *
 * Authentication happens against plex.tv (which issues an account token);
 * all library traffic then goes straight to the user's own server with the
 * X-Plex-Token header. JSON responses are requested via the Accept header
 * (Plex defaults to XML otherwise).
 * API Reference: https://plexapi.dev/
 */
@Singleton
class PlexApiService @Inject constructor(
    baseOkHttpClient: OkHttpClient
) {

    companion object {
        private const val TAG = "PlexApi"
        private const val PLEX_TV_SIGNIN_URL = "https://plex.tv/api/v2/users/signin"
        private const val CLIENT_NAME = "PixelPlayer"
        private const val CLIENT_VERSION = "1.0"
        private const val DEVICE_NAME = "Android"
        private const val CLIENT_IDENTIFIER = "PixelPlayer-Android"

        /** Plex type filter for track items in /library/sections/{id}/all */
        private const val PLEX_TYPE_TRACK = "10"
    }

    @Volatile
    private var credentials: PlexCredentials? = null

    private val okHttpClient: OkHttpClient = baseOkHttpClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // ─── Credentials Management ─────────────────────────────────────────

    fun setCredentials(credentials: PlexCredentials) {
        this.credentials = credentials
        Timber.d("$TAG: Credentials set for server: ${credentials.normalizedServerUrl}, user: ${credentials.username}")
    }

    fun clearCredentials() {
        this.credentials = null
        Timber.d("$TAG: Credentials cleared")
    }

    fun hasCredentials(): Boolean = credentials?.hasToken == true

    fun getServerUrl(): String? = credentials?.normalizedServerUrl

    fun getAuthToken(): String? = credentials?.authToken

    // ─── Authentication ─────────────────────────────────────────────────

    private fun Request.Builder.withPlexHeaders(token: String? = null): Request.Builder {
        header("Accept", "application/json")
        header("X-Plex-Client-Identifier", CLIENT_IDENTIFIER)
        header("X-Plex-Product", CLIENT_NAME)
        header("X-Plex-Version", CLIENT_VERSION)
        header("X-Plex-Device", DEVICE_NAME)
        header("X-Plex-Platform", "Android")
        token?.let { header("X-Plex-Token", it) }
        return this
    }

    /**
     * Sign in to plex.tv with username (or email) and password.
     * Returns the account auth token and user id — the token is valid for
     * every server the account owns or has access to.
     */
    suspend fun signIn(username: String, password: String): Result<Pair<String, String>> {
        return withContext(Dispatchers.IO) {
            try {
                val body = FormBody.Builder()
                    .add("login", username)
                    .add("password", password)
                    .build()

                val request = Request.Builder()
                    .url(PLEX_TV_SIGNIN_URL)
                    .withPlexHeaders()
                    .post(body)
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val message = if (response.code == 401) {
                            "Invalid Plex username or password"
                        } else {
                            "HTTP ${response.code}: ${response.message}"
                        }
                        return@withContext Result.failure(Exception(message))
                    }

                    val responseBody = response.body.string()
                    val json = JSONObject(responseBody)
                    val authToken = json.optString("authToken", "")
                    val userId = json.optString("uuid", json.optLong("id", 0L).toString())

                    if (authToken.isBlank()) {
                        return@withContext Result.failure(Exception("Invalid authentication response"))
                    }

                    Timber.d("$TAG: Authentication successful for user $username")
                    Result.success(Pair(authToken, userId))
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Authentication failed")
                Result.failure(e)
            }
        }
    }

    // ─── Web Auth (plex.tv PIN / link flow) ──────────────────────────────

    /**
     * Create a plex.tv auth PIN. Returns (pinId, code); the code is embedded in
     * the browser auth URL and the id is polled until the user approves.
     */
    suspend fun createAuthPin(): Result<Pair<Long, String>> {
        return withContext(Dispatchers.IO) {
            try {
                val body = FormBody.Builder().add("strong", "true").build()
                val request = Request.Builder()
                    .url("https://plex.tv/api/v2/pins")
                    .withPlexHeaders()
                    .post(body)
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                    }
                    val json = JSONObject(response.body.string())
                    val id = json.optLong("id", -1L)
                    val code = json.optString("code", "")
                    if (id <= 0 || code.isBlank()) {
                        return@withContext Result.failure(Exception("Invalid PIN response"))
                    }
                    Result.success(Pair(id, code))
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to create auth PIN")
                Result.failure(e)
            }
        }
    }

    /** Browser URL where the user approves this app. */
    fun buildAuthUrl(code: String): String {
        return "https://app.plex.tv/auth#?clientID=$CLIENT_IDENTIFIER&code=$code" +
            "&context%5Bdevice%5D%5Bproduct%5D=$CLIENT_NAME" +
            "&context%5Bdevice%5D%5Bdevice%5D=$DEVICE_NAME"
    }

    /** Poll an auth PIN. Success with null token = not approved yet. */
    suspend fun checkAuthPin(pinId: Long): Result<String?> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://plex.tv/api/v2/pins/$pinId")
                    .withPlexHeaders()
                    .get()
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                    }
                    val json = JSONObject(response.body.string())
                    val token = json.optString("authToken", "").takeIf { it.isNotBlank() }
                    Result.success(token)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /** Account details for a token: (uuid, display name). */
    suspend fun getAccountDetails(token: String): Result<Pair<String, String>> {
        return plexTvGet("https://plex.tv/api/v2/user", token).map { body ->
            val json = JSONObject(body)
            val uuid = json.optString("uuid", json.optLong("id", 0L).toString())
            val name = json.optString("title").takeIf { it.isNotBlank() }
                ?: json.optString("username", "Plex user")
            Pair(uuid, name)
        }
    }

    /** Users in the account's Plex Home (empty when Home isn't set up). */
    suspend fun getHomeUsers(token: String): Result<List<PlexHomeUser>> {
        return plexTvGet("https://plex.tv/api/v2/home/users", token).map { body ->
            val users = JSONObject(body).optJSONArray("users") ?: JSONArray()
            (0 until users.length()).mapNotNull { i ->
                val user = users.optJSONObject(i) ?: return@mapNotNull null
                val uuid = user.optString("uuid").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                PlexHomeUser(
                    uuid = uuid,
                    title = user.optString("title", user.optString("username", "User")),
                    isProtected = user.optBoolean("protected", false),
                    isAdmin = user.optBoolean("admin", false)
                )
            }
        }
    }

    /** Switch to a Plex Home user; returns that user's auth token. */
    suspend fun switchHomeUser(token: String, userUuid: String, pin: String?): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val urlBuilder = "https://plex.tv/api/v2/home/users/$userUuid/switch".toHttpUrl()
                    .newBuilder()
                pin?.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("pin", it) }

                val request = Request.Builder()
                    .url(urlBuilder.build())
                    .withPlexHeaders(token = token)
                    .post(FormBody.Builder().build())
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val message = if (response.code == 401 || response.code == 403) {
                            "Wrong PIN for this user"
                        } else {
                            "HTTP ${response.code}: ${response.message}"
                        }
                        return@withContext Result.failure(Exception(message))
                    }
                    val userToken = JSONObject(response.body.string())
                        .optString("authToken", "")
                    if (userToken.isBlank()) {
                        return@withContext Result.failure(Exception("No token in switch response"))
                    }
                    Result.success(userToken)
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Home user switch failed")
                Result.failure(e)
            }
        }
    }

    /** Media servers this token can access, with all their advertised addresses. */
    suspend fun getServers(token: String): Result<List<PlexServerResource>> {
        return getResources(token) { provides -> provides.contains("server") }
    }

    /**
     * Remote-controllable players on the account (Plexamp instances etc.).
     * Players advertise provides=client and/or player.
     */
    suspend fun getPlayers(token: String): Result<List<PlexServerResource>> {
        return getResources(token) { provides ->
            provides.contains("player") || provides.contains("client")
        }
    }

    private suspend fun getResources(
        token: String,
        providesFilter: (String) -> Boolean
    ): Result<List<PlexServerResource>> {
        val url = "https://plex.tv/api/v2/resources".toHttpUrl().newBuilder()
            .addQueryParameter("includeHttps", "1")
            .addQueryParameter("includeRelay", "1")
            .build()
            .toString()

        return plexTvGet(url, token).map { body ->
            val resources = JSONArray(body)
            (0 until resources.length()).mapNotNull { i ->
                val resource = resources.optJSONObject(i) ?: return@mapNotNull null
                if (!providesFilter(resource.optString("provides"))) return@mapNotNull null

                val connectionsJson = resource.optJSONArray("connections") ?: JSONArray()
                val connections = (0 until connectionsJson.length()).mapNotNull { c ->
                    val connection = connectionsJson.optJSONObject(c) ?: return@mapNotNull null
                    val uri = connection.optString("uri").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    PlexServerConnection(
                        uri = uri.trimEnd('/'),
                        isLocal = connection.optBoolean("local", false),
                        isRelay = connection.optBoolean("relay", false)
                    )
                }
                if (connections.isEmpty()) return@mapNotNull null

                PlexServerResource(
                    name = resource.optString("name", "Plex Server"),
                    clientIdentifier = resource.optString("clientIdentifier", ""),
                    accessToken = resource.optString("accessToken").takeIf { it.isNotBlank() },
                    connections = connections,
                    product = resource.optString("product", "")
                )
            }
        }
    }

    /** The active server's stable machine identifier (needed for remote playMedia). */
    suspend fun getServerMachineIdentifier(): Result<String> {
        return requestContainer("/identity").mapCatching { container ->
            container.optString("machineIdentifier").takeIf { it.isNotBlank() }
                ?: throw Exception("Server identity has no machineIdentifier")
        }
    }

    /**
     * Create an audio play queue on the server so a remote player can be
     * pointed at it via Companion playMedia. [metadataIds] is one ratingKey or
     * a comma-separated list (queue order).
     */
    suspend fun createPlayQueue(metadataIds: String, machineIdentifier: String): Result<Long> {
        return withContext(Dispatchers.IO) {
            try {
                val cred = credentials ?: throw IllegalStateException("No credentials configured")
                val uri = "server://$machineIdentifier/com.plexapp.plugins.library/library/metadata/$metadataIds"
                val url = "${cred.normalizedServerUrl}/playQueues".toHttpUrl().newBuilder()
                    .addQueryParameter("type", "audio")
                    .addQueryParameter("uri", uri)
                    .addQueryParameter("shuffle", "0")
                    .addQueryParameter("repeat", "0")
                    .addQueryParameter("continuous", "0")
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .withPlexHeaders(token = cred.authToken)
                    .post(FormBody.Builder().build())
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                    }
                    val container = JSONObject(response.body.string())
                        .optJSONObject("MediaContainer") ?: JSONObject()
                    val playQueueId = container.optLong("playQueueID", -1L)
                    if (playQueueId <= 0) {
                        return@withContext Result.failure(Exception("No playQueueID in response"))
                    }
                    Result.success(playQueueId)
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to create play queue")
                Result.failure(e)
            }
        }
    }

    /** Quick reachability probe of a server address with a short timeout. */
    suspend fun testServerConnection(baseUrl: String, token: String, timeoutSeconds: Long = 4): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val client = okHttpClient.newBuilder()
                    .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .callTimeout(timeoutSeconds + 1, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/identity")
                    .withPlexHeaders(token = token)
                    .get()
                    .build()
                client.newCall(request).execute().use { it.isSuccessful }
            } catch (_: Exception) {
                false
            }
        }
    }

    private suspend fun plexTvGet(url: String, token: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .withPlexHeaders(token = token)
                    .get()
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                    }
                    Result.success(response.body.string())
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: plex.tv request failed")
                Result.failure(e)
            }
        }
    }

    // ─── Core Request Method ─────────────────────────────────────────────

    private suspend fun request(path: String, params: Map<String, String> = emptyMap()): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val cred = credentials ?: throw IllegalStateException("No credentials configured")
                val baseUrl = "${cred.normalizedServerUrl}$path"

                val urlBuilder = baseUrl.toHttpUrl().newBuilder()
                params.forEach { (key, value) ->
                    urlBuilder.addQueryParameter(key, value)
                }

                val request = Request.Builder()
                    .url(urlBuilder.build())
                    .withPlexHeaders(token = cred.authToken)
                    .get()
                    .build()

                Timber.d("$TAG: >>> GET $path")

                okHttpClient.newCall(request).execute().use { response ->
                    val code = response.code
                    val body = response.body.string()

                    if (!response.isSuccessful) {
                        Timber.w("$TAG: <<< HTTP $code for $path")
                        return@withContext Result.failure(Exception("HTTP $code: ${response.message}"))
                    }

                    Timber.d("$TAG: <<< HTTP $code for $path, body length: ${body.length}")
                    Result.success(body)
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: !!! FAILED GET $path")
                Result.failure(e)
            }
        }
    }

    private suspend fun requestContainer(path: String, params: Map<String, String> = emptyMap()): Result<JSONObject> {
        return request(path, params).map { body ->
            JSONObject(body).optJSONObject("MediaContainer") ?: JSONObject()
        }
    }

    private fun JSONObject.metadataList(arrayName: String = "Metadata"): List<JSONObject> {
        val items = optJSONArray(arrayName)
        return (0 until (items?.length() ?: 0)).mapNotNull { items?.optJSONObject(it) }
    }

    // ─── System API ──────────────────────────────────────────────────────

    suspend fun ping(): Result<Boolean> {
        return request("/identity").map { true }
    }

    // ─── Library API ─────────────────────────────────────────────────────

    /**
     * Get the keys of all music ("artist" type) library sections.
     */
    suspend fun getMusicSectionKeys(): Result<List<String>> {
        return requestContainer("/library/sections").map { container ->
            container.metadataList("Directory")
                .filter { it.optString("type") == "artist" }
                .mapNotNull { it.optString("key").takeIf { key -> key.isNotBlank() } }
        }
    }

    /**
     * Get a page of tracks from a music section.
     */
    suspend fun getMusicItems(
        sectionKey: String,
        startIndex: Int = 0,
        limit: Int = 500
    ): Result<Pair<Int, List<JSONObject>>> {
        val params = mapOf(
            "type" to PLEX_TYPE_TRACK,
            "X-Plex-Container-Start" to startIndex.toString(),
            "X-Plex-Container-Size" to limit.toString()
        )

        return requestContainer("/library/sections/$sectionKey/all", params).map { container ->
            val totalCount = container.optInt("totalSize", container.optInt("size", 0))
            Pair(totalCount, container.metadataList())
        }
    }

    /**
     * Get all albums in a music section.
     */
    suspend fun getAlbums(
        sectionKey: String,
        startIndex: Int = 0,
        limit: Int = 500
    ): Result<Pair<Int, List<JSONObject>>> {
        val params = mapOf(
            "type" to "9", // album
            "X-Plex-Container-Start" to startIndex.toString(),
            "X-Plex-Container-Size" to limit.toString()
        )

        return requestContainer("/library/sections/$sectionKey/all", params).map { container ->
            val totalCount = container.optInt("totalSize", container.optInt("size", 0))
            Pair(totalCount, container.metadataList())
        }
    }

    /**
     * Get all artists in a music section.
     */
    suspend fun getArtists(sectionKey: String): Result<List<JSONObject>> {
        return requestContainer("/library/sections/$sectionKey/all", mapOf("type" to "8"))
            .map { it.metadataList() }
    }

    // ─── Playlist API ────────────────────────────────────────────────────

    suspend fun getPlaylists(): Result<List<JSONObject>> {
        return requestContainer("/playlists", mapOf("playlistType" to "audio"))
            .map { it.metadataList() }
    }

    suspend fun getPlaylistItems(playlistId: String): Result<List<JSONObject>> {
        return requestContainer("/playlists/$playlistId/items").map { it.metadataList() }
    }

    // ─── Search API ──────────────────────────────────────────────────────

    suspend fun searchSongs(query: String, limit: Int = 30): Result<List<JSONObject>> {
        val params = mapOf(
            "query" to query,
            "limit" to limit.toString()
        )

        return requestContainer("/hubs/search", params).map { container ->
            val hubs = container.optJSONArray("Hub")
            val trackHub = (0 until (hubs?.length() ?: 0))
                .mapNotNull { hubs?.optJSONObject(it) }
                .firstOrNull { it.optString("type") == "track" }
            trackHub?.metadataList() ?: emptyList()
        }
    }

    // ─── Playback Reporting ──────────────────────────────────────────────

    /**
     * Mark a track as played (increments the server-side play count).
     */
    suspend fun scrobble(ratingKey: String): Result<Unit> {
        val params = mapOf(
            "key" to ratingKey,
            "identifier" to "com.plexapp.plugins.library"
        )
        return request("/:/scrobble", params).map { }
    }

    /**
     * Report playback progress so the server shows "now playing" and keeps
     * On Deck / resume positions accurate.
     *
     * @param state One of "playing", "paused", "stopped", "buffering".
     */
    suspend fun reportTimeline(
        ratingKey: String,
        state: String,
        timeMs: Long,
        durationMs: Long
    ): Result<Unit> {
        val params = mapOf(
            "ratingKey" to ratingKey,
            "key" to "/library/metadata/$ratingKey",
            "state" to state,
            "time" to timeMs.coerceAtLeast(0L).toString(),
            "duration" to durationMs.coerceAtLeast(0L).toString(),
            "identifier" to "com.plexapp.plugins.library"
        )
        return request("/:/timeline", params).map { }
    }

    // ─── Media URLs ──────────────────────────────────────────────────────

    /**
     * Resolve the direct-play stream URL for a track.
     *
     * Plex serves the original file at the Part key path
     * (/library/parts/{partId}/{updatedAt}/file.{ext}), which we look up from
     * the track metadata. Range requests are supported, so seeking works.
     */
    suspend fun getStreamUrl(ratingKey: String): Result<String> {
        val cred = credentials ?: return Result.failure(Exception("No credentials"))

        return requestContainer("/library/metadata/$ratingKey").mapCatching { container ->
            val metadata = container.metadataList().firstOrNull()
                ?: throw Exception("Track $ratingKey not found")
            val partKey = metadata.optJSONArray("Media")
                ?.optJSONObject(0)
                ?.optJSONArray("Part")
                ?.optJSONObject(0)
                ?.optString("key")
                ?.takeIf { it.isNotBlank() }
                ?: throw Exception("No playable part for track $ratingKey")

            "${cred.normalizedServerUrl}$partKey".toHttpUrl().newBuilder()
                .addQueryParameter("X-Plex-Token", cred.authToken)
                .build()
                .toString()
        }
    }

    /**
     * Build a cover art URL for an item, resized server-side by the Plex
     * photo transcoder. Falls back to the item's own thumb resource — valid
     * for albums and artists, but tracks usually have no thumb of their own,
     * so prefer [getImageUrlForPath] with a stored thumb path when available.
     */
    fun getImageUrl(ratingKey: String, maxWidth: Int = 500): String {
        return getImageUrlForPath("/library/metadata/$ratingKey/thumb", maxWidth)
    }

    /**
     * Build a cover art URL for an exact server art path
     * (e.g. "/library/metadata/12345/thumb/1699999999").
     */
    fun getImageUrlForPath(thumbPath: String, maxWidth: Int = 500): String {
        val cred = credentials ?: throw IllegalStateException("No credentials configured")
        return "${cred.normalizedServerUrl}/photo/:/transcode".toHttpUrl().newBuilder()
            .addQueryParameter("width", maxWidth.toString())
            .addQueryParameter("height", maxWidth.toString())
            .addQueryParameter("minSize", "1")
            .addQueryParameter("upscale", "1")
            .addQueryParameter("url", thumbPath)
            .addQueryParameter("X-Plex-Token", cred.authToken)
            .build()
            .toString()
    }
}
