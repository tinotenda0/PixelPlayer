package com.theveloper.pixelplay.data.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import com.theveloper.pixelplay.utils.AlbumArtUtils
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.MessageDigest

class SharedArtworkContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? {
        val appContext = context?.applicationContext ?: return null
        return if (parseSongId(uri, appContext.packageName) != null ||
            parseCloudCoverUri(uri) != null
        ) {
            DEFAULT_CONTENT_TYPE
        } else {
            null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") {
            throw FileNotFoundException("Shared artwork provider is read-only")
        }

        val file = resolveArtworkFile(uri)
            ?: throw FileNotFoundException("No artwork found for uri=$uri")

        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        val fileDescriptor = openFile(uri, mode)
        return AssetFileDescriptor(fileDescriptor, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    private fun resolveArtworkFile(uri: Uri): File? {
        val appContext = context?.applicationContext ?: return null

        parseCloudCoverUri(uri)?.let { coverUri ->
            return resolveCloudArtworkFile(appContext, coverUri)
        }

        val songId = parseSongId(uri, appContext.packageName) ?: return null
        return AlbumArtUtils.ensureAlbumArtCachedFile(
            appContext = appContext,
            songId = songId
        )?.takeIf { it.exists() && it.isFile && it.canRead() }
    }

    /**
     * Serves covers for streaming providers (plex_cover:// etc.) to external
     * controllers like Android Auto, which cannot load app-internal schemes.
     * The image is fetched through the app's Coil pipeline (already handles all
     * provider schemes and their disk caches) and materialized as a JPEG.
     */
    private fun resolveCloudArtworkFile(appContext: Context, coverUri: String): File? {
        val cacheDir = File(appContext.cacheDir, CLOUD_ART_CACHE_DIR).apply { mkdirs() }
        val cacheFile = File(cacheDir, "${sha1(coverUri)}.jpg")
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return cacheFile
        }

        val request = ImageRequest.Builder(appContext)
            .data(coverUri)
            .allowHardware(false)
            .size(CLOUD_ART_SIZE_PX)
            .build()
        val drawable = runBlocking {
            runCatching { appContext.imageLoader.execute(request).drawable }.getOrNull()
        } ?: return null

        return runCatching {
            val bitmap = drawable.toBitmap()
            FileOutputStream(cacheFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            cacheFile.takeIf { it.exists() && it.length() > 0 }
        }.getOrNull()
    }

    companion object {
        private const val AUTHORITY_SUFFIX = ".artwork"
        private const val PATH_SONG = "song"
        private const val PATH_CLOUD = "cloud"
        private const val DEFAULT_CONTENT_TYPE = "image/jpeg"
        private const val CLOUD_ART_CACHE_DIR = "shared_cloud_art"
        private const val CLOUD_ART_SIZE_PX = 512

        /** Provider cover schemes that the app's Coil pipeline knows how to load. */
        private val CLOUD_COVER_SCHEMES = setOf(
            "plex_cover",
            "jellyfin_cover",
            "navidrome_cover",
            "telegram_art"
        )

        fun authority(packageName: String): String = packageName + AUTHORITY_SUFFIX

        /**
         * Wraps an app-internal provider cover URI (e.g. plex_cover://123) into a
         * content:// URI external controllers can open. Returns null for
         * schemes outside the allowlist.
         */
        fun buildCloudArtworkUri(context: Context, coverUri: String): Uri? {
            val scheme = coverUri.substringBefore("://", "").lowercase()
            if (scheme !in CLOUD_COVER_SCHEMES) return null
            val encoded = Base64.encodeToString(
                coverUri.toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            return Uri.parse("content://${authority(context.packageName)}/$PATH_CLOUD/$encoded")
        }

        internal fun parseCloudCoverUri(uri: Uri): String? {
            val segments = uri.pathSegments
            if (segments.size != 2 || segments[0] != PATH_CLOUD) return null
            val decoded = runCatching {
                String(
                    Base64.decode(segments[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                    Charsets.UTF_8
                )
            }.getOrNull() ?: return null
            val scheme = decoded.substringBefore("://", "").lowercase()
            return decoded.takeIf { scheme in CLOUD_COVER_SCHEMES }
        }

        private fun sha1(value: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun buildSongUri(
            context: Context,
            songId: Long,
            cacheBustToken: String? = null
        ): Uri = buildSongUri(context.packageName, songId, cacheBustToken)

        internal fun buildSongUri(
            packageName: String,
            songId: Long,
            cacheBustToken: String? = null
        ): Uri {
            return Uri.parse(buildSongUriString(packageName, songId, cacheBustToken))
        }

        internal fun buildSongUriString(
            packageName: String,
            songId: Long,
            cacheBustToken: String? = null
        ): String {
            val baseUri = "content://${authority(packageName)}/$PATH_SONG/$songId"
            return cacheBustToken
                ?.takeIf { it.isNotBlank() }
                ?.let { "$baseUri?t=$it" }
                ?: baseUri
        }

        internal fun parseSongId(uri: Uri, packageName: String? = null): Long? {
            return parseSongId(uri.toString(), packageName)
        }

        internal fun parseSongId(uriString: String, packageName: String? = null): Long? {
            val expectedPrefix = packageName
                ?.let(::authority)
                ?.let { "content://$it/$PATH_SONG/" }

            if (expectedPrefix != null && !uriString.startsWith(expectedPrefix)) {
                return null
            }

            val basePrefix = expectedPrefix ?: run {
                val authoritySeparator = "://"
                val schemeSplit = uriString.indexOf(authoritySeparator)
                if (schemeSplit < 0) return null
                val pathStart = uriString.indexOf('/', schemeSplit + authoritySeparator.length)
                if (pathStart < 0) return null
                val pathPrefix = uriString.substring(pathStart)
                if (!pathPrefix.startsWith("/$PATH_SONG/")) return null
                uriString.substring(0, pathStart) + "/$PATH_SONG/"
            }

            val songIdSegment = uriString
                .removePrefix(basePrefix)
                .substringBefore('?')
                .substringBefore('/')

            if (songIdSegment.isBlank()) {
                return null
            }
            return songIdSegment.toLongOrNull()
        }
    }
}
