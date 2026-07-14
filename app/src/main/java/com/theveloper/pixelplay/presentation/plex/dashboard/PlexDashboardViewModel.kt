package com.theveloper.pixelplay.presentation.plex.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.database.PlexPlaylistEntity
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.plex.PlexDownloadManager
import com.theveloper.pixelplay.data.plex.PlexRepository
import com.theveloper.pixelplay.data.plex.model.PlexAccount
import com.theveloper.pixelplay.data.plex.model.PlexPlayerDevice
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class PlexDashboardViewModel @Inject constructor(
    private val repository: PlexRepository,
    private val downloadManager: PlexDownloadManager
) : ViewModel() {

    val playlists: StateFlow<List<PlexPlaylistEntity>> = repository.getPlaylists()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    private val _selectedPlaylistSongs = MutableStateFlow<List<Song>>(emptyList())
    val selectedPlaylistSongs: StateFlow<List<Song>> = _selectedPlaylistSongs.asStateFlow()

    val username: String? get() = repository.username
    val serverUrl: String? get() = repository.serverUrl
    val isLoggedIn: StateFlow<Boolean> = repository.isLoggedInFlow

    init {
        syncAllPlaylistsAndSongs()
    }

    fun syncAllPlaylistsAndSongs() {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncMessage.value = "Syncing all playlists and songs..."
            val result = repository.syncAllPlaylistsAndSongs()
            result.fold(
                onSuccess = { summary ->
                    _syncMessage.value = if (summary.failedPlaylistCount == 0) {
                        "Synced ${summary.playlistCount} playlists, ${summary.syncedSongCount} songs"
                    } else {
                        "Synced ${summary.playlistCount} playlists, ${summary.syncedSongCount} songs (${summary.failedPlaylistCount} failed)"
                    }
                },
                onFailure = { _syncMessage.value = "Sync failed: ${it.message}" }
            )
            _isSyncing.value = false
        }
    }

    fun syncPlaylists() {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncMessage.value = "Syncing playlists..."
            val result = repository.syncPlaylists()
            result.fold(
                onSuccess = { _syncMessage.value = "Synced ${it.size} playlists" },
                onFailure = { _syncMessage.value = "Sync failed: ${it.message}" }
            )
            _isSyncing.value = false
        }
    }

    fun syncPlaylistSongs(playlistId: String) {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncMessage.value = "Syncing songs..."
            val result = repository.syncPlaylistSongs(playlistId)
            result.fold(
                onSuccess = { count ->
                    try {
                        repository.syncUnifiedLibrarySongsFromPlex()
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to sync unified library after playlist sync")
                    }
                    _syncMessage.value = "Synced $count songs"
                },
                onFailure = { _syncMessage.value = "Sync failed: ${it.message}" }
            )
            _isSyncing.value = false
        }
    }

    fun loadPlaylistSongs(playlistId: String) {
        viewModelScope.launch {
            repository.getPlaylistSongs(playlistId).collect { songs ->
                _selectedPlaylistSongs.value = songs
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            repository.deletePlaylist(playlistId)
        }
    }

    fun clearSyncMessage() {
        _syncMessage.value = null
    }

    // ─── Accounts ─────────────────────────────────────────────────────────

    val accounts: StateFlow<List<PlexAccount>> = repository.accountsFlow
    val activeAccount: StateFlow<PlexAccount?> = repository.activeAccountFlow

    fun switchAccount(accountId: String) {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncMessage.value = "Switching account..."
            repository.switchAccount(accountId).fold(
                onSuccess = { name -> _syncMessage.value = "Switched to $name — syncing…" },
                onFailure = { _syncMessage.value = "Switch failed: ${it.message}" }
            )
            _isSyncing.value = false
        }
    }

    fun removeAccount(accountId: String) {
        viewModelScope.launch {
            repository.removeAccount(accountId)
        }
    }

    // ─── Offline downloads ────────────────────────────────────────────────

    val downloadCount: StateFlow<Int> = downloadManager.downloadCount
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val downloadTotalBytes: StateFlow<Long> = downloadManager.totalSizeBytes
        .stateIn(viewModelScope, SharingStarted.Lazily, 0L)

    val downloadQueueProgress: StateFlow<PlexDownloadManager.QueueProgress?> =
        downloadManager.queueProgress

    fun downloadPlaylist(playlistId: String) {
        viewModelScope.launch {
            val plexIds = repository.getPlaylistSongPlexIds(playlistId)
            downloadManager.pinSongs(plexIds)
        }
    }

    fun downloadLibrary() {
        viewModelScope.launch {
            val plexIds = repository.getAllSongPlexIds()
            downloadManager.pinSongs(plexIds)
        }
    }

    fun removeAllDownloads() {
        viewModelScope.launch {
            downloadManager.removeAllDownloads()
        }
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
        }
    }

    // ─── Remote control (Plexamp / Plex Companion) ────────────────────────

    data class RemoteNowPlaying(
        val state: String,
        val positionMs: Long,
        val durationMs: Long,
        val title: String?,
        val artist: String?,
        val volume: Int?
    )

    private val _remotePlayers = MutableStateFlow<List<PlexPlayerDevice>>(emptyList())
    val remotePlayers: StateFlow<List<PlexPlayerDevice>> = _remotePlayers.asStateFlow()

    private val _isLoadingRemotePlayers = MutableStateFlow(false)
    val isLoadingRemotePlayers: StateFlow<Boolean> = _isLoadingRemotePlayers.asStateFlow()

    private val _selectedRemotePlayer = MutableStateFlow<PlexPlayerDevice?>(null)
    val selectedRemotePlayer: StateFlow<PlexPlayerDevice?> = _selectedRemotePlayer.asStateFlow()

    private val _remoteNowPlaying = MutableStateFlow<RemoteNowPlaying?>(null)
    val remoteNowPlaying: StateFlow<RemoteNowPlaying?> = _remoteNowPlaying.asStateFlow()

    private var remotePollJob: Job? = null
    private var lastRemoteRatingKey: String? = null
    private var lastRemoteTitle: String? = null
    private var lastRemoteArtist: String? = null

    fun loadRemotePlayers() {
        viewModelScope.launch {
            _isLoadingRemotePlayers.value = true
            repository.getRemotePlayers().fold(
                onSuccess = { _remotePlayers.value = it },
                onFailure = {
                    Timber.w(it, "Failed to load remote players")
                    _remotePlayers.value = emptyList()
                }
            )
            _isLoadingRemotePlayers.value = false
        }
    }

    fun selectRemotePlayer(device: PlexPlayerDevice) {
        _selectedRemotePlayer.value = device
        _remoteNowPlaying.value = null
        lastRemoteRatingKey = null
        startRemotePolling(device)
    }

    fun clearRemotePlayer() {
        remotePollJob?.cancel()
        remotePollJob = null
        _selectedRemotePlayer.value = null
        _remoteNowPlaying.value = null
    }

    private fun startRemotePolling(device: PlexPlayerDevice) {
        remotePollJob?.cancel()
        remotePollJob = viewModelScope.launch {
            while (isActive) {
                val timeline = repository.getRemoteTimeline(device).getOrNull()
                if (timeline != null) {
                    if (timeline.ratingKey != lastRemoteRatingKey) {
                        lastRemoteRatingKey = timeline.ratingKey
                        val song = timeline.ratingKey?.let { repository.getSongByRatingKey(it) }
                        lastRemoteTitle = song?.title
                        lastRemoteArtist = song?.displayArtist
                    }
                    _remoteNowPlaying.value = RemoteNowPlaying(
                        state = timeline.state,
                        positionMs = timeline.timeMs,
                        durationMs = timeline.durationMs,
                        title = lastRemoteTitle,
                        artist = lastRemoteArtist,
                        volume = timeline.volume
                    )
                } else {
                    _remoteNowPlaying.value = null
                }
                delay(2_500)
            }
        }
    }

    fun sendRemoteCommand(command: String) {
        val device = _selectedRemotePlayer.value ?: return
        // Optimistic play/pause flip so the button reacts instantly.
        _remoteNowPlaying.value?.let { current ->
            when (command) {
                "play" -> _remoteNowPlaying.value = current.copy(state = "playing")
                "pause" -> _remoteNowPlaying.value = current.copy(state = "paused")
            }
        }
        viewModelScope.launch {
            repository.sendRemoteCommand(device, command)
        }
    }

    fun setRemoteVolume(volume: Int) {
        val device = _selectedRemotePlayer.value ?: return
        _remoteNowPlaying.value?.let {
            _remoteNowPlaying.value = it.copy(volume = volume)
        }
        viewModelScope.launch {
            repository.setRemoteVolume(device, volume)
        }
    }

    override fun onCleared() {
        remotePollJob?.cancel()
        super.onCleared()
    }
}
