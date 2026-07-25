package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.navidrome.NavidromeDownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the dedicated Downloads screen. Everything here is sourced from
 * [NavidromeDownloadManager], which reconstructs songs from metadata stored at pin time — so this
 * screen is fully functional with no network and no synced library (the offline-mode contract).
 */
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadManager: NavidromeDownloadManager
) : ViewModel() {

    data class DownloadsUiState(
        val songs: ImmutableList<Song> = persistentListOf(),
        val totalSizeBytes: Long = 0L,
        val activeCount: Int = 0
    )

    val uiState: StateFlow<DownloadsUiState> = combine(
        downloadManager.downloadedSongs,
        downloadManager.totalSizeBytes,
        downloadManager.queueProgress
    ) { songs, sizeBytes, progress ->
        val active = progress?.let { (it.total - it.completed - it.failed).coerceAtLeast(0) } ?: 0
        DownloadsUiState(
            songs = songs.toImmutableList(),
            totalSizeBytes = sizeBytes,
            activeCount = active
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DownloadsUiState()
    )

    fun remove(song: Song) {
        val id = song.navidromeId ?: return
        viewModelScope.launch { downloadManager.removeDownload(id) }
    }

    fun removeAll() {
        viewModelScope.launch { downloadManager.removeAllDownloads() }
    }
}
