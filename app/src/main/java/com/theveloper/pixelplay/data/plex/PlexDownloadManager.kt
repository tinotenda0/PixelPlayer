package com.theveloper.pixelplay.data.plex

import android.content.Context
import com.theveloper.pixelplay.data.database.PlexDownloadDao
import com.theveloper.pixelplay.data.database.PlexDownloadEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads ("pins") Plex tracks for offline playback.
 *
 * Completed downloads are recorded in the plex_downloads table and stored as
 * files under the app-specific storage. [DualPlayerEngine] checks
 * [getLocalFilePath] before falling back to the streaming proxy, so pinned
 * songs play instantly and fully offline.
 */
@Singleton
class PlexDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: PlexRepository,
    private val downloadDao: PlexDownloadDao,
    okHttpClient: OkHttpClient
) {
    data class QueueProgress(
        val completed: Int,
        val failed: Int,
        val total: Int
    ) {
        val isActive: Boolean get() = completed + failed < total
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueMutex = Mutex()
    private var workerJob: Job? = null
    private val pendingIds = ArrayDeque<String>()
    private val queuedIdSet = ConcurrentHashMap.newKeySet<String>()

    // Fast lookup cache plexId -> filePath, mirrored from the DAO.
    private val localPathCache = ConcurrentHashMap<String, String>()
    @Volatile
    private var cacheLoaded = false

    private val _queueProgress = MutableStateFlow<QueueProgress?>(null)
    val queueProgress: StateFlow<QueueProgress?> = _queueProgress.asStateFlow()

    val downloadCount: Flow<Int> = downloadDao.getDownloadCount()
    val totalSizeBytes: Flow<Long> = downloadDao.getTotalSizeBytes()

    // Long read timeout: whole audio files are streamed to disk in one call.
    private val httpClient: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val downloadDir: File
        get() {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            return File(base, "plex_downloads").apply { mkdirs() }
        }

    private suspend fun ensureCacheLoaded() {
        if (cacheLoaded) return
        downloadDao.getAllDownloadsList().forEach { entity ->
            localPathCache[entity.plexId] = entity.filePath
        }
        cacheLoaded = true
    }

    /**
     * Returns the local file path for a pinned track, or null if it isn't
     * downloaded (or its file has been removed externally).
     */
    suspend fun getLocalFilePath(plexId: String): String? {
        ensureCacheLoaded()
        val path = localPathCache[plexId] ?: return null
        val file = File(path)
        if (!file.exists() || file.length() == 0L) {
            // Stale record — file was cleared by the system or the user.
            localPathCache.remove(plexId)
            downloadDao.delete(plexId)
            return null
        }
        return path
    }

    suspend fun isDownloaded(plexId: String): Boolean = getLocalFilePath(plexId) != null

    suspend fun getDownloadedIds(): Set<String> {
        ensureCacheLoaded()
        return localPathCache.keys.toSet()
    }

    /**
     * Queue the given tracks for download. Already-downloaded and
     * already-queued ids are skipped.
     */
    fun pinSongs(plexIds: List<String>) {
        if (plexIds.isEmpty()) return
        scope.launch {
            ensureCacheLoaded()
            val newIds = queueMutex.withLock {
                val fresh = plexIds
                    .distinct()
                    .filter { it.isNotBlank() && it !in localPathCache.keys && queuedIdSet.add(it) }
                pendingIds.addAll(fresh)
                fresh
            }
            if (newIds.isEmpty()) return@launch

            _queueProgress.value = (_queueProgress.value?.takeIf { it.isActive })
                ?.copy(total = _queueProgress.value!!.total + newIds.size)
                ?: QueueProgress(completed = 0, failed = 0, total = newIds.size)

            startWorkerIfNeeded()
        }
    }

    private fun startWorkerIfNeeded() {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch {
            while (true) {
                val nextId = queueMutex.withLock { pendingIds.removeFirstOrNull() } ?: break
                val success = try {
                    downloadTrack(nextId)
                } catch (e: Exception) {
                    Timber.w(e, "PlexDownloadManager: download failed for $nextId")
                    false
                } finally {
                    queuedIdSet.remove(nextId)
                }
                _queueProgress.value = _queueProgress.value?.let {
                    if (success) it.copy(completed = it.completed + 1)
                    else it.copy(failed = it.failed + 1)
                }
            }
        }
    }

    private suspend fun downloadTrack(plexId: String): Boolean {
        if (localPathCache.containsKey(plexId)) return true
        if (!repository.isLoggedIn) return false

        val streamUrl = try {
            repository.getStreamUrl(plexId)
        } catch (e: Exception) {
            Timber.w(e, "PlexDownloadManager: could not resolve stream URL for $plexId")
            return false
        }

        val request = Request.Builder().url(streamUrl).get().build()
        val tempFile = File(downloadDir, "$plexId.part")

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.w("PlexDownloadManager: HTTP ${response.code} downloading $plexId")
                return@use false
            }

            val mimeType = response.header("Content-Type")?.substringBefore(';')?.trim()
            val extension = mimeTypeToExtension(mimeType, streamUrl)

            response.body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, bufferSize = 128 * 1024)
                }
            }

            if (tempFile.length() == 0L) {
                tempFile.delete()
                return@use false
            }

            val finalFile = File(downloadDir, "$plexId.$extension")
            if (finalFile.exists()) finalFile.delete()
            if (!tempFile.renameTo(finalFile)) {
                tempFile.delete()
                return@use false
            }

            downloadDao.insert(
                PlexDownloadEntity(
                    plexId = plexId,
                    filePath = finalFile.absolutePath,
                    mimeType = mimeType,
                    sizeBytes = finalFile.length(),
                    downloadedAt = System.currentTimeMillis()
                )
            )
            localPathCache[plexId] = finalFile.absolutePath
            Timber.d("PlexDownloadManager: downloaded $plexId (${finalFile.length()} bytes)")
            true
        }
    }

    suspend fun removeDownload(plexId: String) {
        ensureCacheLoaded()
        localPathCache.remove(plexId)?.let { File(it).delete() }
        downloadDao.delete(plexId)
    }

    suspend fun removeAllDownloads() {
        queueMutex.withLock {
            pendingIds.clear()
            queuedIdSet.clear()
        }
        workerJob?.cancel()
        workerJob = null
        _queueProgress.value = null

        ensureCacheLoaded()
        downloadDao.deleteAll()
        localPathCache.clear()
        downloadDir.listFiles()?.forEach { it.delete() }
    }

    private fun mimeTypeToExtension(mimeType: String?, url: String): String {
        // Prefer the extension from the original Plex part path when present.
        val urlExtension = url.substringBefore('?')
            .substringAfterLast('.', missingDelimiterValue = "")
            .takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } }
        if (urlExtension != null) return urlExtension.lowercase()

        return when (mimeType?.lowercase()) {
            "audio/mpeg" -> "mp3"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/mp4", "audio/aac" -> "m4a"
            "audio/ogg" -> "ogg"
            "audio/opus" -> "opus"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/webm" -> "webm"
            else -> "audio"
        }
    }
}
