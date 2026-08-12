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
import com.theveloper.pixelplay.data.navidrome.NavidromeRepository
import com.theveloper.pixelplay.data.stats.ListeningEventOutbox
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.json.JSONObject
import timber.log.Timber

/**
 * Drains [ListeningEventOutbox] through the gateway's bulk importListeningEvents endpoint —
 * the fallback path for a play recorded with no connectivity. Safe to retry wholesale: the
 * gateway's insert is idempotent per eventId (curation.record_listening_event).
 */
@HiltWorker
class ListeningEventUploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val navidromeRepository: NavidromeRepository,
    private val outbox: ListeningEventOutbox
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val pending = outbox.pending()
        if (pending.isEmpty()) return Result.success()

        val batch = pending.take(BATCH_SIZE)
        val payload = batch.map { event ->
            JSONObject().apply {
                put("eventId", event.eventId)
                put("songId", event.navidromeId)
                put("title", event.title)
                put("artist", event.artist)
                put("album", event.album)
                put("cover", event.cover)
                put("durationMs", event.durationMs)
                put("startTime", event.startTimestamp)
                put("endTime", event.endTimestamp)
            }
        }

        val result = navidromeRepository.importListeningEvents(payload)
        if (result.isFailure) {
            Timber.w(result.exceptionOrNull(), "ListeningEventUploadWorker: batch upload failed")
            return Result.retry()
        }

        outbox.remove(batch.map { it.eventId })
        // More left than fit in one batch (or more arrived while this ran) — come back for the
        // rest rather than declaring success with a partially-drained queue.
        return if (pending.size > batch.size) Result.retry() else Result.success()
    }

    companion object {
        const val WORK_NAME = "com.theveloper.pixelplay.data.worker.ListeningEventUploadWorker"
        private const val BATCH_SIZE = 300

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        private fun drainWork() = OneTimeWorkRequestBuilder<ListeningEventUploadWorker>()
            .setConstraints(constraints)
            .build()

        /** KEEP: if a drain is already queued/running, a second failed upload shouldn't pile on
         *  a duplicate request — the one already queued will pick up everything once it runs. */
        fun enqueue(workManager: WorkManager) {
            workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, drainWork())
        }
    }
}
