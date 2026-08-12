package com.theveloper.pixelplay.data.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.diagnostics.AdvancedPerformanceDiagnostics
import com.theveloper.pixelplay.data.navidrome.NavidromeRepository
import com.theveloper.pixelplay.data.service.PlaybackActivityTracker
import com.theveloper.pixelplay.utils.AlbumArtCacheManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Syncs the Navidrome gateway library into the unified `songs` table and cleans up the
 * album-art cache. Runs as a WorkManager job so it survives process death and respects
 * network/charging constraints for the heavier periodic pass.
 */
@HiltWorker
class SyncWorker
@AssistedInject
constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val musicDao: MusicDao,
        private val navidromeRepository: NavidromeRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val forceRefresh = inputData.getBoolean(INPUT_FORCE_REFRESH, false)
            val startTime = System.currentTimeMillis()

            // Defer opportunistic (non-forced) syncs while music is playing, same backoff
            // window as before: WorkManager's exponential backoff (30s -> 60s -> 2m -> 4m ->
            // 8m -> ...) means MAX_PLAYBACK_DEFERRALS retries cover ~16 minutes. A user-forced
            // refresh (pull-to-refresh, Settings) always runs immediately.
            if (!forceRefresh &&
                PlaybackActivityTracker.isPlaybackActive &&
                runAttemptCount < MAX_PLAYBACK_DEFERRALS
            ) {
                Log.d(TAG, "Deferring sync (playback active, attempt=$runAttemptCount)")
                return@withContext Result.retry()
            }

            if (navidromeRepository.isLoggedIn) {
                setProgress(workDataOf(PROGRESS_PHASE to SyncProgress.SyncPhase.SYNCING_CLOUD.ordinal))
                syncNavidromeData(forceNetworkFetch = forceRefresh)
            }

            setProgress(workDataOf(PROGRESS_PHASE to SyncProgress.SyncPhase.CLEANING_CACHE.ordinal))
            val allSongIds = musicDao.getAllSongIds().toSet()
            AlbumArtCacheManager.cleanOrphanedCacheFiles(applicationContext, allSongIds)

            val totalSongs = musicDao.getSongCountOnce()
            AdvancedPerformanceDiagnostics.recordEventIfEnabled(
                type = AdvancedPerformanceDiagnostics.EventTypes.WORKER,
                name = "sync_worker_success"
            ) {
                mapOf(
                    "durationMs" to (System.currentTimeMillis() - startTime).toString(),
                    "totalSongs" to totalSongs.toString(),
                    "forceRefresh" to forceRefresh.toString()
                )
            }
            Result.success(workDataOf(OUTPUT_TOTAL_SONGS to totalSongs.toLong()))
        } catch (e: Exception) {
            Log.e(TAG, "Error during sync", e)
            AdvancedPerformanceDiagnostics.recordEventIfEnabled(
                type = AdvancedPerformanceDiagnostics.EventTypes.WORKER,
                name = "sync_worker_failure"
            ) {
                mapOf("error" to (e.message ?: e.javaClass.simpleName))
            }
            Result.failure()
        }
    }

    private suspend fun syncNavidromeData(forceNetworkFetch: Boolean) {
        if (!navidromeRepository.isLoggedIn) return

        val lastSync = navidromeRepository.lastFullSyncTime
        val currentTime = System.currentTimeMillis()
        val cacheEmpty = navidromeRepository.cachedLibrarySongCount() == 0

        // Skip the server fetch only if we synced recently AND we actually have a cached library.
        // An empty cache means we never successfully populated it (or it was reset) — in that case
        // always fetch, regardless of the 24h threshold, so the Library/home can't get stuck blank.
        if (!forceNetworkFetch &&
            currentTime - lastSync < NavidromeRepository.SYNC_THRESHOLD_MS &&
            !cacheEmpty
        ) {
            Log.d(TAG, "Skipping Navidrome server sync - recent and cache present.")
            navidromeRepository.syncUnifiedLibrarySongsFromNavidrome()
            return
        }

        Log.i(TAG, "Syncing Navidrome data from server...")
        try {
            // Fetch playlists and songs from the Navidrome server, then sync to unified library
            val result = navidromeRepository.syncAllPlaylistsAndSongs()
            result.fold(
                onSuccess = { summary ->
                    Log.i(TAG, "Navidrome sync complete: ${summary.playlistCount} playlists, ${summary.syncedSongCount} songs synced (${summary.failedPlaylistCount} failed)")
                },
                onFailure = { e ->
                    Log.w(TAG, "Navidrome server sync failed, falling back to local cache sync", e)
                    // Fallback: at least sync what we already have cached
                    navidromeRepository.syncUnifiedLibrarySongsFromNavidrome()
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync Navidrome data", e)
        }
    }

    companion object {
        const val WORK_NAME = "com.theveloper.pixelplay.data.worker.SyncWorker"
        // Distinct unique name so background maintenance never feeds the WORK_NAME-bound
        // isSyncing/syncProgress flows — the loading indicator stays silent for it.
        const val PERIODIC_MAINTENANCE_WORK_NAME =
            "com.theveloper.pixelplay.data.worker.SyncWorker.PeriodicMaintenance"
        private const val TAG = "SyncWorker"
        const val INPUT_FORCE_REFRESH = "input_force_refresh"
        // Non-forced syncs back off this many times while playback is active before running
        // anyway.
        private const val MAX_PLAYBACK_DEFERRALS = 5

        // Progress reporting constants
        const val PROGRESS_PHASE = "progress_phase"
        const val OUTPUT_TOTAL_SONGS = "output_total_songs"

        /** Opportunistic sync: respects the freshness threshold, defers around playback. */
        fun syncWork() =
                OneTimeWorkRequestBuilder<SyncWorker>()
                        .setInputData(workDataOf(INPUT_FORCE_REFRESH to false))
                        .build()

        /** User-initiated sync (pull-to-refresh, Settings actions): always hits the server. */
        fun forceRefreshWork() =
                OneTimeWorkRequestBuilder<SyncWorker>()
                        .setInputData(workDataOf(INPUT_FORCE_REFRESH to true))
                        .build()

        // Daily maintenance (album-art cache cleanup + cloud sync). Runs while charging on an
        // unmetered network so it stays invisible to the user.
        fun periodicMaintenanceWork(): PeriodicWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build()
            return PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS)
                .setInputData(workDataOf(INPUT_FORCE_REFRESH to false))
                .setConstraints(constraints)
                .build()
        }
    }
}
