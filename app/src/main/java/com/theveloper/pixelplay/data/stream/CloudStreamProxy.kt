package com.theveloper.pixelplay.data.stream

import android.net.Uri
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Abstract base class for local HTTP proxy servers that stream cloud music audio.
 *
 * Subclasses define the route, ID type, validation, allowed hosts, and URL resolution.
 * The base class handles the full Ktor CIO server lifecycle, URL caching, and OkHttp
 * proxying with security checks via [CloudStreamSecurity].
 *
 * @param K The song identifier type (e.g. [String] for Navidrome songId)
 */
abstract class CloudStreamProxy<K : Any>(
    okHttpClient: OkHttpClient
) {
    /**
     * The injected client is tuned for API calls, and its 8s read timeout is far too tight for a
     * multi-minute audio body: a momentary stall on a mobile network would abort the track. Share
     * its connection pool and dispatcher via newBuilder(), but give reads room to breathe.
     */
    private val okHttpClient: OkHttpClient = okHttpClient.newBuilder()
        .readTimeout(STREAM_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    // ─── Subclass Configuration ────────────────────────────────────────

    protected abstract val allowedHostSuffixes: Set<String>
    protected abstract val cacheExpirationMs: Long
    protected abstract val proxyTag: String

    /** Route path registered with Ktor, e.g. "/navidrome/{songId}" */
    protected abstract val routePath: String
    /** The parameter name inside the route path, e.g. "songId" */
    protected abstract val routeParamName: String
    /** URI scheme this proxy handles, e.g. "navidrome" */
    protected abstract val uriScheme: String
    /** URL path prefix for proxy URLs, e.g. "/navidrome" */
    protected abstract val routePrefix: String

    /** Parse the raw route parameter string into the typed ID, or null if invalid */
    protected abstract fun parseRouteParam(value: String): K?
    /** Validate whether the given ID is acceptable */
    protected abstract fun validateId(id: K): Boolean
    /** Convert the ID to a string for use in URLs */
    protected abstract fun formatIdForUrl(id: K): String
    /** Resolve the actual streaming URL for the given song ID */
    protected abstract suspend fun resolveStreamUrl(id: K): String?

    // ─── Server State ──────────────────────────────────────────────────

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var actualPort: Int = 0
    private val proxyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null

    private val urlCache = ConcurrentHashMap<K, CachedUrl>()

    private companion object {
        const val STREAM_READ_TIMEOUT_SECONDS = 30L
    }

    private data class CachedUrl(val url: String, val timestamp: Long, val expirationMs: Long) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > expirationMs
    }

    // ─── Public API ────────────────────────────────────────────────────

    fun isReady(): Boolean = actualPort > 0

    fun startIfNeeded() {
        if (isReady() || startJob?.isActive == true) return
        start()
    }

    suspend fun awaitReady(timeoutMs: Long = 10_000L): Boolean {
        if (isReady()) return true
        val stepMs = 50L
        var elapsed = 0L
        while (elapsed < timeoutMs) {
            if (isReady()) return true
            delay(stepMs)
            elapsed += stepMs
        }
        return false
    }

    suspend fun ensureReady(timeoutMs: Long = 10_000L): Boolean {
        startIfNeeded()
        return awaitReady(timeoutMs)
    }

    fun getProxyUrl(id: K): String {
        if (actualPort == 0) return ""
        if (!validateId(id)) return ""
        return "http://127.0.0.1:$actualPort$routePrefix/${formatIdForUrl(id)}"
    }

    /**
     * Parse a cloud URI (e.g. "navidrome://xxxx") and return
     * the local proxy URL. Returns null if the URI doesn't match this proxy's scheme.
     */
    fun resolveUri(uriString: String): String? {
        val uri = Uri.parse(uriString)
        if (uri.scheme != uriScheme) return null
        val rawId = extractIdFromUri(uri) ?: return null
        val id = parseRouteParam(rawId) ?: return null
        if (!validateId(id)) return null
        return getProxyUrl(id)
    }

    fun start() {
        startJob?.cancel()
        startJob = proxyScope.launch {
            try {
                val freePort = ServerSocket(0).use { it.localPort }
                val createdServer = createServer(freePort)
                createdServer.start(wait = false)
                server = createdServer
                actualPort = freePort
                Timber.d("$proxyTag started on port $actualPort")
            } catch (_: CancellationException) {
                Timber.d("$proxyTag start cancelled")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start $proxyTag")
            }
        }
    }

    fun stop() {
        startJob?.cancel()
        startJob = null
        proxyScope.coroutineContext.cancelChildren()
        server?.stop(1000, 2000)
        server = null
        actualPort = 0
        urlCache.clear()
        Timber.d("$proxyTag stopped")
    }

    // ─── Overridable Hooks ─────────────────────────────────────────────

    /** Extract the raw ID string from a parsed URI. Override for custom URI layouts. */
    protected open fun extractIdFromUri(uri: Uri): String? = uri.host

    // ─── Internal ──────────────────────────────────────────────────────

    protected suspend fun getOrFetchStreamUrl(id: K): String? {
        urlCache[id]?.let { cached ->
            if (!cached.isExpired()) return cached.url
        }
        return resolveStreamUrl(id)?.also { url ->
            urlCache[id] = CachedUrl(url, System.currentTimeMillis(), cacheExpirationMs)
        }
    }

    /**
     * Copies [body] into [writeChunk] until it ends. Returns null on a clean finish, or the
     * upstream failure that interrupted it — write failures propagate instead, since those mean
     * the player closed the connection and there is nothing left to resume into.
     */
    private suspend fun pumpUpstream(
        body: okhttp3.ResponseBody,
        buffer: ByteArray,
        writeChunk: suspend (ByteArray, Int) -> Unit
    ): IOException? {
        val input = body.byteStream()
        while (true) {
            val read = try {
                withContext(Dispatchers.IO) { input.read(buffer) }
            } catch (e: IOException) {
                runCatching { body.close() }
                return e
            }
            if (read == -1) {
                runCatching { body.close() }
                return null
            }
            writeChunk(buffer, read)
        }
    }

    /**
     * Re-requests the tail of the stream. Only a 206 is usable: a 200 means the server ignored
     * the Range and would replay the whole file, duplicating everything already delivered.
     */
    private suspend fun reopenUpstream(streamUrl: String, rangeHeader: String): okhttp3.ResponseBody? {
        val response: Response = try {
            withContext(Dispatchers.IO) {
                okHttpClient.newCall(
                    Request.Builder().url(streamUrl).header("Range", rangeHeader).build()
                ).execute()
            }
        } catch (e: IOException) {
            Timber.tag(proxyTag).w(e, "Resume request failed")
            return null
        }
        if (response.code != 206) {
            Timber.tag(proxyTag).w("Resume refused: upstream answered %d, not 206", response.code)
            runCatching { response.close() }
            return null
        }
        return response.body
    }

    private fun createServer(port: Int): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
        return embeddedServer(CIO, port = port, host = "127.0.0.1") {
            routing {
                get(routePath) {
                    val rawParam = call.parameters[routeParamName]
                    val id = rawParam?.let { parseRouteParam(it) }
                    if (id == null || !validateId(id)) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid ID")
                        return@get
                    }

                    try {
                        val rangeValidation = CloudStreamSecurity.validateRangeHeader(
                            call.request.headers["Range"]
                        )
                        if (!rangeValidation.isValid) {
                            call.respond(
                                HttpStatusCode(416, "Range Not Satisfiable"),
                                "Invalid range header"
                            )
                            return@get
                        }

                        val streamUrl = getOrFetchStreamUrl(id)
                        if (streamUrl.isNullOrBlank()) {
                            call.respond(HttpStatusCode.NotFound, "No stream URL available")
                            return@get
                        }
                        if (!CloudStreamSecurity.isSafeRemoteStreamUrl(
                                url = streamUrl,
                                allowedHostSuffixes = allowedHostSuffixes,
                                allowHttpForAllowedHosts = true
                            )
                        ) {
                            call.respond(HttpStatusCode.BadGateway, "Rejected upstream stream URL")
                            return@get
                        }

                        val requestBuilder = Request.Builder().url(streamUrl)
                        rangeValidation.normalizedHeader?.let {
                            requestBuilder.header("Range", it)
                        }

                        val response = withContext(Dispatchers.IO) {
                            okHttpClient.newCall(requestBuilder.build()).execute()
                        }

                        response.use { upstream ->
                            if (upstream.code != 200 && upstream.code != 206) {
                                call.respond(
                                    CloudStreamSecurity.mapUpstreamStatusToProxyStatus(upstream.code),
                                    "Upstream stream request failed"
                                )
                                return@get
                            }

                            val body = upstream.body
                            val contentTypeHeader = upstream.header("Content-Type")

                            if (!CloudStreamSecurity.isSupportedAudioContentType(contentTypeHeader)) {
                                call.respond(
                                    HttpStatusCode.BadGateway,
                                    "Unsupported stream content type"
                                )
                                return@get
                            }

                            val contentLength = upstream.header("Content-Length")
                            if (!CloudStreamSecurity.isAcceptableContentLength(contentLength)) {
                                call.respond(
                                    HttpStatusCode(413, "Payload Too Large"),
                                    "Stream content too large"
                                )
                                return@get
                            }

                            val contentRange = upstream.header("Content-Range")
                            val acceptRanges = upstream.header("Accept-Ranges")
                            val responseContentType = contentTypeHeader
                                ?.substringBefore(';')
                                ?.trim()
                                ?.let { raw ->
                                    runCatching { ContentType.parse(raw) }.getOrNull()
                                }
                                ?: ContentType.Audio.Any

                            if (upstream.code == 206) {
                                call.response.status(HttpStatusCode.PartialContent)
                            } else {
                                call.response.status(HttpStatusCode.OK)
                            }
                            call.response.header("Accept-Ranges", acceptRanges ?: "bytes")
                            contentLength?.let { call.response.header("Content-Length", it) }
                            contentRange?.let { call.response.header("Content-Range", it) }

                            val clientStart = rangeValidation.startInclusive ?: 0L
                            val clientEnd = rangeValidation.endInclusive

                            call.respondBytesWriter(contentType = responseContentType) {
                                val buffer = ByteArray(64 * 1024)
                                var delivered = 0L
                                var attempt = 0
                                var current = body

                                while (true) {
                                    // Upstream read failures are recoverable and handled below;
                                    // a write failure means the player hung up, which is not.
                                    val upstreamFailure = pumpUpstream(current, buffer) { chunk, length ->
                                        writeFully(chunk, 0, length)
                                        delivered += length
                                    } ?: break

                                    if (!CloudStreamResume.canResume(
                                            isSuffixRange = rangeValidation.isSuffixRange,
                                            deliveredBytes = delivered,
                                            attempt = attempt
                                        )
                                    ) {
                                        throw upstreamFailure
                                    }

                                    attempt++
                                    Timber.tag(proxyTag).w(
                                        "Upstream died %d bytes in (%s) — resuming, attempt %d",
                                        delivered,
                                        upstreamFailure.toString(),
                                        attempt
                                    )
                                    delay(CloudStreamResume.backoffMs(attempt))
                                    current = reopenUpstream(
                                        streamUrl = streamUrl,
                                        rangeHeader = CloudStreamResume.resumeRangeHeader(
                                            clientStart = clientStart,
                                            clientEndInclusive = clientEnd,
                                            deliveredBytes = delivered
                                        )
                                    ) ?: throw upstreamFailure
                                }
                            }
                        }
                    } catch (e: Exception) {
                        val msg = e.toString()
                        if (msg.contains("ChannelWriteException") ||
                            msg.contains("ClosedChannelException") ||
                            msg.contains("Broken pipe") ||
                            msg.contains("JobCancellationException")
                        ) {
                            // Client disconnected, normal behavior
                        } else {
                            Timber.w(e, "$proxyTag stream failed")
                        }
                    }
                }
            }
        }
    }
}
