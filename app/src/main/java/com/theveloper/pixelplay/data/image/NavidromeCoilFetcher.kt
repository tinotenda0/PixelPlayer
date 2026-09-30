package com.theveloper.pixelplay.data.image

import android.net.Uri
import coil.ImageLoader
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.theveloper.pixelplay.data.navidrome.NavidromeRepository
import com.theveloper.pixelplay.data.network.navidrome.NavidromeApiService
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Path.Companion.toPath
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * Custom Coil Fetcher for Navidrome album art.
 * Handles URIs in format: navidrome_cover://coverArtId
 *
 * Converts the cover art ID to a full HTTP URL using the Navidrome API
 * and downloads the image to a local cache.
 */
class NavidromeCoilFetcher(
    private val uri: Uri,
    private val repository: NavidromeRepository,
    private val okHttpClient: OkHttpClient,
    private val cacheDir: File
) : Fetcher {

    companion object {
        private const val TAG = "NavidromeCoilFetcher"
        private val recentlyLoggedFailures = ConcurrentHashMap<String, Long>()
        private const val LOG_FAILURE_COOLDOWN_MS = 60_000L

        private fun shouldLogFailure(key: String): Boolean {
            val now = System.currentTimeMillis()
            val lastLogged = recentlyLoggedFailures[key]
            return if (lastLogged == null || now - lastLogged > LOG_FAILURE_COOLDOWN_MS) {
                recentlyLoggedFailures[key] = now
                if (recentlyLoggedFailures.size > 100) {
                    recentlyLoggedFailures.entries.removeIf { now - it.value > LOG_FAILURE_COOLDOWN_MS }
                }
                true
            } else {
                false
            }
        }

        /** Covers share the app's cacheDir with everything else, so trimming goes by prefix. */
        internal const val COVER_FILE_PREFIX = "navidrome_cover_"

        /** Past this, the least recently used covers go. At the 500px the gateway now serves,
         *  that is a few thousand covers — a large library's worth of scrolling. */
        internal const val COVER_CACHE_MAX_BYTES = 150L * 1024 * 1024

        /** A `.tmp` older than this belongs to a write that died (process killed mid-save),
         *  not one in progress — a save takes milliseconds. */
        internal const val STALE_TMP_MS = 60_000L

        /** A trim lists and stats every cached cover, so it runs at most this often. */
        private const val TRIM_INTERVAL_MS = 10 * 60_000L
        private val lastTrimAtMs = AtomicLong(0L)

        /**
         * Deletes abandoned temp files, then the least recently used covers (by mtime — cache
         * hits refresh it) until the cache fits in [maxBytes].
         */
        internal fun trimCoverCache(
            dir: File,
            maxBytes: Long = COVER_CACHE_MAX_BYTES,
            nowMs: Long = System.currentTimeMillis()
        ) {
            val files = dir.listFiles { f -> f.isFile && f.name.startsWith(COVER_FILE_PREFIX) }
                ?: return
            val (temps, covers) = files.partition { it.name.endsWith(".tmp") }
            temps.filter { nowMs - it.lastModified() > STALE_TMP_MS }.forEach { it.delete() }

            var total = covers.sumOf { it.length() }
            if (total <= maxBytes) return
            for (file in covers.sortedBy { it.lastModified() }) {
                val length = file.length()
                if (file.delete()) total -= length
                if (total <= maxBytes) break
            }
        }

        private fun maybeTrimCoverCache(dir: File) {
            val now = System.currentTimeMillis()
            val last = lastTrimAtMs.get()
            if (now - last < TRIM_INTERVAL_MS || !lastTrimAtMs.compareAndSet(last, now)) return
            runCatching { trimCoverCache(dir, nowMs = now) }
                .onFailure { Timber.w(it, "$TAG: Cover cache trim failed") }
        }
    }

    override suspend fun fetch(): FetchResult? {
        Timber.v("$TAG: Fetching $uri")

        // Parse URI: navidrome_cover://coverArtId
        val coverArtId = uri.host ?: uri.path?.removePrefix("/")
        if (coverArtId.isNullOrBlank()) {
            Timber.w("$TAG: Invalid URI format: $uri")
            return null
        }

        // Check if user is logged in
        if (!repository.isLoggedIn) {
            Timber.v("$TAG: Not logged in, skipping fetch")
            return null
        }

        // Check for size parameter
        val sizeParam = uri.getQueryParameter("size")?.toIntOrNull() ?: 500

        // Check cache first
        val cachedFile = File(cacheDir, "$COVER_FILE_PREFIX${coverArtId}_$sizeParam.jpg")
        if (cachedFile.exists() && cachedFile.length() > 0) {
            Timber.v("$TAG: Using cached cover for $coverArtId")
            // Marks it recently used, so the size-capped trim evicts covers nobody looks at.
            cachedFile.setLastModified(System.currentTimeMillis())
            return SourceResult(
                source = coil.decode.ImageSource(
                    file = cachedFile.absolutePath.toPath(),
                    fileSystem = okio.FileSystem.SYSTEM
                ),
                mimeType = "image/jpeg",
                dataSource = coil.decode.DataSource.DISK
            )
        }

        // Get the cover art URL from the repository
        val coverArtUrl = repository.getCoverArtUrl(coverArtId, sizeParam)
        if (coverArtUrl.isNullOrBlank()) {
            if (shouldLogFailure("no_url_$coverArtId")) {
                Timber.w("$TAG: No cover art URL for $coverArtId")
            }
            return null
        }

        // Download the image
        return try {
            downloadImage(coverArtUrl, cachedFile)
        } catch (e: Exception) {
            if (shouldLogFailure("download_$coverArtId")) {
                Timber.w(e, "$TAG: Failed to download cover art for $coverArtId")
            }
            null
        }
    }

    private suspend fun downloadImage(url: String, cacheFile: File): FetchResult? {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        return try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("$TAG: HTTP ${response.code} for ${cacheFile.name}")
                    return null
                }

                val bytes = response.body.bytes()

                if (bytes.isEmpty()) {
                    Timber.w("$TAG: Empty response body for ${cacheFile.name}")
                    return null
                }

                // Save to cache via a temp file + rename. fetch() trusts any non-empty file at
                // the final name, so a write cut short (process killed, request cancelled, disk
                // full) must never land there: it would be served as a broken cover forever.
                // The temp name is unique so two concurrent fetches of one cover can't collide.
                val tmpFile = File.createTempFile("${cacheFile.name}.", ".tmp", cacheFile.parentFile)
                try {
                    FileOutputStream(tmpFile).use { fos ->
                        fos.write(bytes)
                        fos.fd.sync()
                    }
                    if (!tmpFile.renameTo(cacheFile)) {
                        Timber.w("$TAG: Could not move cover into cache: ${cacheFile.name}")
                        return null
                    }
                } finally {
                    tmpFile.delete() // no-op once renamed
                }

                Timber.v("$TAG: Cached cover art (${bytes.size} bytes)")
                maybeTrimCoverCache(cacheDir)

                SourceResult(
                    source = coil.decode.ImageSource(
                        file = cacheFile.absolutePath.toPath(),
                        fileSystem = okio.FileSystem.SYSTEM
                    ),
                    mimeType = response.header("Content-Type") ?: "image/jpeg",
                    dataSource = coil.decode.DataSource.NETWORK
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Failed to download image ${cacheFile.name}")
            null
        }
    }

    /**
     * Factory for creating NavidromeCoilFetcher instances.
     * Registered with Coil's ImageLoader to handle navidrome_cover:// URIs.
     */
    class Factory @Inject constructor(
        private val repository: NavidromeRepository,
        private val okHttpClient: OkHttpClient
    ) : Fetcher.Factory<Uri> {

        private var cacheDir: File? = null

        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            return if (data.scheme == "navidrome_cover") {
                val cache = cacheDir ?: options.context.cacheDir.also { cacheDir = it }
                NavidromeCoilFetcher(data, repository, okHttpClient, cache)
            } else {
                null
            }
        }
    }
}
