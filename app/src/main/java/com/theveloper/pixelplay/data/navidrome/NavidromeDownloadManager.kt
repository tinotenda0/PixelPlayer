package com.theveloper.pixelplay.data.navidrome

import android.content.Context
import com.theveloper.pixelplay.data.database.NavidromeDownloadDao
import com.theveloper.pixelplay.data.database.NavidromeDownloadEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
 * Downloads ("pins") Subsonic/YouTube tracks for offline playback — Spotify-style.
 *
 * Completed downloads are recorded in the navidrome_downloads table and stored as files
 * under app-specific storage. [com.theveloper.pixelplay.data.service.player.DualPlayerEngine]
 * checks [getLocalFilePath] before resolving the streaming proxy, so pinned songs play
 * instantly and fully offline. Works for both real server ids and `yt-<videoId>` on-demand
 * tracks (the gateway serves the audio either way).
 */
@Singleton
class NavidromeDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: NavidromeRepository,
    private val downloadDao: NavidromeDownloadDao,
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

    // Fast lookup cache navidromeId -> filePath, mirrored from the DAO.
    private val localPathCache = ConcurrentHashMap<String, String>()
    @Volatile
    private var cacheLoaded = false

    private val _queueProgress = MutableStateFlow<QueueProgress?>(null)
    val queueProgress: StateFlow<QueueProgress?> = _queueProgress.asStateFlow()

    val downloadCount: Flow<Int> = downloadDao.getDownloadCount()
    val totalSizeBytes: Flow<Long> = downloadDao.getTotalSizeBytes()

    /** Reactive set of downloaded song ids, for showing per-track download state in the UI. */
    val downloadedIds: Flow<Set<String>> =
        downloadDao.getAllDownloads().map { list -> list.map { it.navidromeId }.toSet() }

    // Long read timeout: whole audio files are streamed to disk in one call.
    private val httpClient: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val downloadDir: File
        get() {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            return File(base, "navidrome_downloads").apply { mkdirs() }
        }

    private suspend fun ensureCacheLoaded() {
        if (cacheLoaded) return
        downloadDao.getAllDownloadsList().forEach { entity ->
            localPathCache[entity.navidromeId] = entity.filePath
        }
        cacheLoaded = true
    }

    /**
     * Returns the local file path for a pinned track, or null if it isn't downloaded
     * (or its file has been removed externally).
     */
    suspend fun getLocalFilePath(navidromeId: String): String? {
        ensureCacheLoaded()
        val path = localPathCache[navidromeId] ?: return null
        val file = File(path)
        if (!file.exists() || file.length() == 0L) {
            // Stale record — file was cleared by the system or the user.
            localPathCache.remove(navidromeId)
            downloadDao.delete(navidromeId)
            return null
        }
        return path
    }

    suspend fun isDownloaded(navidromeId: String): Boolean = getLocalFilePath(navidromeId) != null

    suspend fun getDownloadedIds(): Set<String> {
        ensureCacheLoaded()
        return localPathCache.keys.toSet()
    }

    /**
     * Queue the given tracks for download. Already-downloaded and already-queued ids are skipped.
     */
    fun pinSongs(navidromeIds: List<String>) {
        if (navidromeIds.isEmpty()) return
        scope.launch {
            ensureCacheLoaded()
            // Enqueue AND decide whether to (re)start the worker under one lock, so a worker that
            // is draining its last item can't finish between "add" and "check isActive" and strand
            // the new id (lost-wakeup), and two callers can't both launch a worker.
            queueMutex.withLock {
                val fresh = navidromeIds
                    .distinct()
                    .filter { it.isNotBlank() && it !in localPathCache.keys && queuedIdSet.add(it) }
                if (fresh.isEmpty()) return@withLock
                pendingIds.addAll(fresh)
                _queueProgress.update { cur ->
                    if (cur != null && cur.isActive) cur.copy(total = cur.total + fresh.size)
                    else QueueProgress(completed = 0, failed = 0, total = fresh.size)
                }
                if (workerJob?.isActive != true) {
                    workerJob = scope.launch { drainQueue() }
                }
            }
        }
    }

    private suspend fun drainQueue() {
        while (true) {
            // Dequeue and the "no more work -> stop" decision happen under the same lock that
            // pinSongs uses to check workerJob, so the start/stop handshake is race-free.
            val nextId = queueMutex.withLock {
                val id = pendingIds.removeFirstOrNull()
                if (id == null) workerJob = null
                id
            } ?: break
            val success = try {
                downloadTrack(nextId)
            } catch (e: Exception) {
                Timber.w(e, "NavidromeDownloadManager: download failed for $nextId")
                false
            } finally {
                queuedIdSet.remove(nextId)
            }
            _queueProgress.update { cur ->
                cur?.let { if (success) it.copy(completed = it.completed + 1) else it.copy(failed = it.failed + 1) }
            }
        }
    }

    private suspend fun downloadTrack(navidromeId: String): Boolean {
        if (localPathCache.containsKey(navidromeId)) return true
        if (!repository.isLoggedIn) return false

        val streamUrl = try {
            repository.getStreamUrl(navidromeId)
        } catch (e: Exception) {
            Timber.w(e, "NavidromeDownloadManager: could not resolve stream URL for $navidromeId")
            return false
        }

        val request = Request.Builder().url(streamUrl).get().build()
        val safeName = navidromeId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val tempFile = File(downloadDir, "$safeName.part")

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.w("NavidromeDownloadManager: HTTP ${response.code} downloading $navidromeId")
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

            val finalFile = File(downloadDir, "$safeName.$extension")
            if (finalFile.exists()) finalFile.delete()
            if (!tempFile.renameTo(finalFile)) {
                tempFile.delete()
                return@use false
            }

            downloadDao.insert(
                NavidromeDownloadEntity(
                    navidromeId = navidromeId,
                    filePath = finalFile.absolutePath,
                    mimeType = mimeType,
                    sizeBytes = finalFile.length(),
                    downloadedAt = System.currentTimeMillis()
                )
            )
            localPathCache[navidromeId] = finalFile.absolutePath
            Timber.d("NavidromeDownloadManager: downloaded $navidromeId (${finalFile.length()} bytes)")
            true
        }
    }

    suspend fun removeDownload(navidromeId: String) {
        ensureCacheLoaded()
        localPathCache.remove(navidromeId)?.let { File(it).delete() }
        downloadDao.delete(navidromeId)
    }

    suspend fun removeAllDownloads() {
        queueMutex.withLock {
            pendingIds.clear()
            queuedIdSet.clear()
        }
        // Wait for any in-flight download to actually stop before wiping — otherwise it can
        // finish writing a file AFTER the wipe and leave an orphan on disk.
        workerJob?.cancelAndJoin()
        workerJob = null
        _queueProgress.value = null

        ensureCacheLoaded()
        downloadDao.deleteAll()
        localPathCache.clear()
        downloadDir.listFiles()?.forEach { it.delete() }
    }

    private fun mimeTypeToExtension(mimeType: String?, url: String): String {
        // Only trust a real audio extension from the URL path (e.g. Plex-style ".../track.flac").
        // The gateway streams from ".../rest/stream.view", whose ".view" must NOT become the file
        // extension — fall through to the Content-Type mapping in that case.
        val known = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "webm", "mp4")
        val urlExtension = url.substringBefore('?')
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .takeIf { it in known }
        if (urlExtension != null) return urlExtension

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
