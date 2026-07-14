package com.theveloper.pixelplay.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.theveloper.pixelplay.data.database.LyricsDao
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.database.toSong
import com.theveloper.pixelplay.data.repository.LyricsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * One-shot bulk lyrics prefetch for the whole library.
 *
 * Walks every song that has no stored lyrics and runs the same search-based
 * remote fetch the manual dialog uses, persisting hits to the lyrics table.
 * Constrained to un-metered networks, and paced with an extra per-song delay
 * on top of the repository's own LRCLIB rate limiting.
 */
@HiltWorker
class LyricsPrefetchWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val musicDao: MusicDao,
    private val lyricsDao: LyricsDao,
    private val lyricsRepository: LyricsRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val songs = musicDao.getAllSongsList().map { it.toSong() }

        val songIdsWithLyrics = songs
            .mapNotNull { it.id.toLongOrNull() }
            .chunked(SQLITE_IN_CHUNK)
            .flatMap { lyricsDao.getSongIdsWithLyrics(it) }
            .toHashSet()

        val missing = songs.filter { song ->
            val id = song.id.toLongOrNull() ?: return@filter false
            id !in songIdsWithLyrics && song.lyrics.isNullOrBlank()
        }

        Timber.d("LyricsPrefetchWorker: ${missing.size} of ${songs.size} songs lack lyrics")
        if (missing.isEmpty()) {
            return Result.success(workDataOf(OUTPUT_FETCHED to 0, OUTPUT_SCANNED to 0))
        }

        var fetched = 0
        missing.forEachIndexed { index, song ->
            if (isStopped) return Result.success(
                workDataOf(OUTPUT_FETCHED to fetched, OUTPUT_SCANNED to index)
            )

            setProgress(
                workDataOf(
                    PROGRESS_CURRENT to index + 1,
                    PROGRESS_TOTAL to missing.size,
                    PROGRESS_FETCHED to fetched
                )
            )

            val result = runCatching { lyricsRepository.fetchFromRemote(song) }.getOrNull()
            if (result?.isSuccess == true) {
                fetched++
            }

            // Politeness pacing towards LRCLIB beyond the built-in limiter.
            delay(PER_SONG_DELAY_MS)
        }

        Timber.d("LyricsPrefetchWorker: fetched lyrics for $fetched of ${missing.size} songs")
        return Result.success(
            workDataOf(OUTPUT_FETCHED to fetched, OUTPUT_SCANNED to missing.size)
        )
    }

    companion object {
        const val WORK_NAME = "lyrics_prefetch_work"

        const val PROGRESS_CURRENT = "progress_current"
        const val PROGRESS_TOTAL = "progress_total"
        const val PROGRESS_FETCHED = "progress_fetched"
        const val OUTPUT_FETCHED = "output_fetched"
        const val OUTPUT_SCANNED = "output_scanned"

        private const val SQLITE_IN_CHUNK = 900
        private const val PER_SONG_DELAY_MS = 600L

        fun oneTimeWork() = OneTimeWorkRequestBuilder<LyricsPrefetchWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
    }
}
