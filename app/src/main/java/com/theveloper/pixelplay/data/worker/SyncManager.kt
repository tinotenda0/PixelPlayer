package com.theveloper.pixelplay.data.worker

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data class representing the progress of the sync operation.
 */
data class SyncProgress(
    val isRunning: Boolean = false,
    val isCompleted: Boolean = false,
    val phase: SyncPhase = SyncPhase.IDLE
) {
    enum class SyncPhase {
        IDLE,
        CLEANING_CACHE,
        SYNCING_CLOUD
    }
}

@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferencesRepository: UserPreferencesRepository
) {
    private val workManager = WorkManager.getInstance(context)
    private val sharingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val isSyncing: Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.WORK_NAME)
            .map { workInfos ->
                val isRunning = workInfos.any { it.state == WorkInfo.State.RUNNING }
                // A freshly enqueued worker (runAttemptCount == 0) is about to start, so it
                // counts as syncing. An ENQUEUED worker with runAttemptCount > 0 is sitting in
                // retry backoff — the only retry path is SyncWorker deferring a non-forced sync
                // while playback is active (see SyncWorker.doWork). It does no work during that
                // window (up to ~16 min of exponential backoff), so we keep the "Syncing…"
                // indicator off instead of showing it indefinitely.
                val isFreshlyEnqueued = workInfos.any {
                    it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 0
                }
                isRunning || isFreshlyEnqueued
            }
            .distinctUntilChanged()
            .shareIn(
                scope = sharingScope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                replay = 1
            )

    init {
        observeAppForeground()
        schedulePeriodicMaintenance()
    }

    /**
     * Schedules the once-a-day maintenance (album-art cache + cloud sync). Uses a dedicated
     * unique name distinct from [SyncWorker.WORK_NAME], so it never drives the foreground sync
     * indicator. KEEP preserves the existing schedule across launches.
     */
    private fun schedulePeriodicMaintenance() {
        workManager.enqueueUniquePeriodicWork(
            SyncWorker.PERIODIC_MAINTENANCE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            SyncWorker.periodicMaintenanceWork()
        )
    }

    /**
     * Flow that exposes the detailed sync progress.
     */
    val syncProgress: Flow<SyncProgress> =
        workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.WORK_NAME)
            .map { workInfos ->
                val runningWork = workInfos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                val succeededWork = workInfos.firstOrNull { it.state == WorkInfo.State.SUCCEEDED }
                val enqueuedWork = workInfos.firstOrNull { it.state == WorkInfo.State.ENQUEUED }

                when {
                    runningWork != null -> {
                        val phaseOrdinal = runningWork.progress.getInt(SyncWorker.PROGRESS_PHASE, 0)
                        val phase = try {
                            SyncProgress.SyncPhase.entries[phaseOrdinal]
                        } catch (e: IndexOutOfBoundsException) {
                            SyncProgress.SyncPhase.IDLE
                        }
                        SyncProgress(isRunning = true, isCompleted = false, phase = phase)
                    }
                    succeededWork != null -> {
                        SyncProgress(isRunning = false, isCompleted = true)
                    }
                    enqueuedWork != null -> {
                        // Mirror isSyncing: a retry-backoff enqueue (runAttemptCount > 0) is a
                        // sync deferred while playback is active and does no work, so don't
                        // surface it as running. Only a fresh enqueue waiting to start does.
                        if (enqueuedWork.runAttemptCount == 0) {
                            SyncProgress(isRunning = true, isCompleted = false)
                        } else {
                            SyncProgress()
                        }
                    }
                    else -> SyncProgress()
                }
            }
            .distinctUntilChanged()
            .shareIn(
                scope = sharingScope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                replay = 1
            )

    /**
     * Emits `true` while the worker is performing maintenance: album-art cache cleanup and
     * cloud-source synchronization. Drives the slim linear indicator under [LibraryActionRow].
     */
    val isPerformingMaintenance: Flow<Boolean> = syncProgress
        .map { progress ->
            progress.isRunning && progress.phase != SyncProgress.SyncPhase.IDLE
        }
        .distinctUntilChanged()
        .shareIn(
            scope = sharingScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            replay = 1
        )

    /**
     * Startup sync: respects the freshness threshold, so a launch shortly after the last sync
     * is a no-op.
     */
    fun sync() {
        sharingScope.launch {
            val now = System.currentTimeMillis()
            val lastSyncTimestamp = userPreferencesRepository.getLastSyncTimestamp()
            val shouldRunSync =
                lastSyncTimestamp <= 0L || (now - lastSyncTimestamp) >= MIN_SYNC_INTERVAL_MS

            if (!shouldRunSync) {
                val ageSeconds = (now - lastSyncTimestamp) / 1000
                Log.d(TAG, "Skipping startup sync (last sync ${ageSeconds}s ago)")
                return@launch
            }

            Log.i(TAG, "Startup sync requested")
            enqueueSyncWork(SyncWorker.syncWork(), ExistingWorkPolicy.KEEP)
        }
    }

    /**
     * Forces an immediate sync against the server, replacing any in-flight sync work. Used for
     * pull-to-refresh and every explicit "sync now" action in Settings.
     */
    fun forceRefresh() {
        Log.i(TAG, "Force refresh requested")
        enqueueSyncWork(SyncWorker.forceRefreshWork(), ExistingWorkPolicy.REPLACE)
    }

    private fun observeAppForeground() {
        // ProcessLifecycleOwner is application-scoped; the observer and this @Singleton both
        // live for the whole process, so registering once here cannot leak.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                sync()
            }
        })
    }

    private fun enqueueSyncWork(
        request: androidx.work.OneTimeWorkRequest,
        policy: ExistingWorkPolicy
    ) {
        workManager.enqueueUniqueWork(SyncWorker.WORK_NAME, policy, request)
    }

    companion object {
        private const val TAG = "SyncManager"
        private const val MIN_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 hours
    }
}
