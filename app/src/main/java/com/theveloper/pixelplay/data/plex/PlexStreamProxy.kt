package com.theveloper.pixelplay.data.plex

import android.net.Uri
import com.theveloper.pixelplay.data.stream.CloudStreamProxy
import com.theveloper.pixelplay.data.stream.CloudStreamSecurity
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlexStreamProxy @Inject constructor(
    private val repository: PlexRepository,
    okHttpClient: OkHttpClient
) : CloudStreamProxy<String>(okHttpClient) {

    override val allowedHostSuffixes: Set<String>
        get() = repository.serverUrl?.toHttpUrlOrNull()?.host?.let { setOf(it) } ?: emptySet()

    override val cacheExpirationMs = 30L * 60 * 1000

    override val proxyTag = "PlexStreamProxy"
    override val routePath = "/plex/{ratingKey}"
    override val routeParamName = "ratingKey"
    override val uriScheme = "plex"
    override val routePrefix = "/plex"

    override fun parseRouteParam(value: String): String? =
        value.takeIf { it.isNotBlank() }

    override fun validateId(id: String): Boolean =
        CloudStreamSecurity.validatePlexRatingKey(id)

    override fun formatIdForUrl(id: String): String = id

    override suspend fun resolveStreamUrl(id: String): String? {
        return try {
            repository.getStreamUrl(id)
        } catch (e: Exception) {
            Timber.w(e, "PlexStreamProxy: Failed to resolve stream URL for item $id")
            null
        }
    }

    override fun extractIdFromUri(uri: Uri): String? =
        uri.host ?: uri.path?.removePrefix("/")

    fun resolvePlexUri(uriString: String): String? = resolveUri(uriString)

    suspend fun warmUpStreamUrl(uriString: String) {
        val uri = Uri.parse(uriString)
        if (uri.scheme != "plex") return
        val ratingKey = uri.host ?: uri.path?.removePrefix("/") ?: return
        if (!CloudStreamSecurity.validatePlexRatingKey(ratingKey)) return
        try {
            getOrFetchStreamUrl(ratingKey)
        } catch (e: Exception) {
            Timber.w(e, "warmUpStreamUrl failed for $ratingKey")
        }
    }
}
