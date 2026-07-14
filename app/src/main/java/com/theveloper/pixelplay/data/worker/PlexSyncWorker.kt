package com.theveloper.pixelplay.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.theveloper.pixelplay.data.plex.PlexRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Background sync for the Plex library and playlists.
 *
 * Runs either as a one-time request (from the dashboard) or as the periodic
 * refresh scheduled by [PlexRepository] while an account is connected, so the
 * local mirror stays fresh without opening the dashboard.
 */
@HiltWorker
class PlexSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: PlexRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (!repository.isLoggedIn) {
            Timber.d("PlexSyncWorker: Not logged in, skipping sync")
            return Result.success()
        }

        Timber.d("PlexSyncWorker: Starting background sync")
        return try {
            val summary = repository.syncAllPlaylistsAndSongs().getOrThrow()
            Timber.d(
                "PlexSyncWorker: Synced ${summary.playlistCount} playlists, " +
                    "${summary.syncedSongCount} songs (${summary.failedPlaylistCount} failed)"
            )
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "PlexSyncWorker: Sync failed")
            Result.retry()
        }
    }

    companion object {
        const val PERIODIC_WORK_NAME = "plex_periodic_sync"
        const val ONE_TIME_WORK_NAME = "plex_one_time_sync"
        const val ERROR_MESSAGE = "error_message"

        private val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun periodicWork(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<PlexSyncWorker>(24, TimeUnit.HOURS)
                .setConstraints(networkConstraints)
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()

        fun oneTimeWork() = OneTimeWorkRequestBuilder<PlexSyncWorker>()
            .setConstraints(networkConstraints)
            .build()
    }
}
