package com.theveloper.pixelplay.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.navidrome.NavidromeRepository
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.stats.PlaybackStatsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.json.JSONObject
import timber.log.Timber

/**
 * One-time, best-effort upload of this device's pre-gateway-Stats local history
 * ([PlaybackStatsRepository.exportEventsForBackup]) into the gateway's listening_events table,
 * gated by [UserPreferencesRepository.STATS_MIGRATION_COMPLETED] so it only ever runs once per
 * device. Each historical event's app-local songId is resolved back to a gateway song id via
 * the local library cache ([MusicDao]) — a song no longer cached (never gateway-sourced, or
 * since removed from the catalog) is silently skipped, not an error. Accepted risk (per product
 * decision): this attributes the device's whole history to whichever account happens to be
 * logged in when it runs, which misattributes history on a device more than one household
 * member has used.
 */
@HiltWorker
class StatsHistoryMigrationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val playbackStatsRepository: PlaybackStatsRepository,
    private val musicDao: MusicDao,
    private val navidromeRepository: NavidromeRepository,
    private val userPreferencesRepository: UserPreferencesRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (userPreferencesRepository.isStatsMigrationCompleted()) return Result.success()
        if (!navidromeRepository.isLoggedIn) return Result.retry()

        val localEvents = playbackStatsRepository.exportEventsForBackup()
        val resolved = localEvents.mapNotNull { toImportPayload(it) }

        for (batch in resolved.chunked(BATCH_SIZE)) {
            val result = navidromeRepository.importListeningEvents(batch)
            if (result.isFailure) {
                Timber.w(result.exceptionOrNull(), "StatsHistoryMigrationWorker: batch failed")
                return Result.retry()
            }
        }

        userPreferencesRepository.setStatsMigrationCompleted(true)
        return Result.success()
    }

    private suspend fun toImportPayload(event: PlaybackStatsRepository.PlaybackEvent): JSONObject? {
        val song = event.songId.toLongOrNull()?.let { musicDao.getSongByIdOnce(it) } ?: return null
        val navidromeId = song.contentUriString
            .takeIf { it.startsWith(NAVIDROME_URI_PREFIX) }
            ?.removePrefix(NAVIDROME_URI_PREFIX)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val endTime = event.endTimestamp ?: event.timestamp
        val durationMs = event.durationMs
        val startTime = event.startTimestamp ?: (endTime - durationMs)
        if (durationMs <= 0L || endTime <= 0L) return null

        return JSONObject().apply {
            // Deterministic id: a crashed/resent batch is free to resend (the gateway's insert
            // is idempotent per eventId), no partial-progress bookkeeping needed here.
            put("eventId", "migrated-$navidromeId-$startTime-$endTime")
            put("songId", navidromeId)
            put("title", song.title)
            put("artist", song.artistName)
            put("album", song.albumName)
            put("cover", song.albumArtUriString.orEmpty())
            put("durationMs", durationMs)
            put("startTime", startTime)
            put("endTime", endTime)
        }
    }

    companion object {
        const val WORK_NAME = "com.theveloper.pixelplay.data.worker.StatsHistoryMigrationWorker"
        private const val BATCH_SIZE = 300
        private const val NAVIDROME_URI_PREFIX = "navidrome://"

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        private fun migrationWork() = OneTimeWorkRequestBuilder<StatsHistoryMigrationWorker>()
            .setConstraints(constraints)
            .build()

        fun enqueue(workManager: WorkManager) {
            workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, migrationWork())
        }
    }
}
