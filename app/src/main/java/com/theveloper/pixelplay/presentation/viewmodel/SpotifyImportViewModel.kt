package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.theveloper.pixelplay.data.navidrome.NavidromeRepository
import com.theveloper.pixelplay.data.navidrome.SpotifyImportOptions
import com.theveloper.pixelplay.data.navidrome.SpotifyImportProgress
import com.theveloper.pixelplay.data.navidrome.SpotifyPreview
import com.theveloper.pixelplay.data.worker.SyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the Spotify import: link the account via a browser OAuth consent (the gateway holds the
 * secret and stores the token), then run a background import of playlists / liked / taste / history.
 *
 * No credentials are ever typed here — the user approves access on Spotify's own page. Only import
 * metadata is read; playback stays on YouTube Music.
 */
@HiltViewModel
class SpotifyImportViewModel @Inject constructor(
    private val navidromeRepository: NavidromeRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    enum class Phase {
        LOADING, UNCONFIGURED, NOT_LINKED, AWAITING_APPROVAL, LINKED, SELECTING,
        IMPORTING, DONE, ERROR
    }

    data class UiState(
        val phase: Phase = Phase.LOADING,
        val accountName: String = "",
        val message: String = "",
        val busy: Boolean = false,
        val progress: SpotifyImportProgress? = null,
        // Selection screen
        val preview: SpotifyPreview? = null,
        val selectedPlaylistIds: Set<String> = emptySet(),
        val likedSelected: Boolean = true,
        val artistsSelected: Boolean = true,
        val historySelected: Boolean = true
    ) {
        /** At least one thing chosen — the Import button's enabled state. */
        val hasSelection: Boolean
            get() = selectedPlaylistIds.isNotEmpty() || likedSelected || artistsSelected || historySelected
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    // One-shot "open this URL in a browser" events (the VM has no Context).
    private val _openUrl = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openUrl: SharedFlow<String> = _openUrl.asSharedFlow()

    private var linkPollJob: Job? = null
    private var importPollJob: Job? = null

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(phase = Phase.LOADING) }
            val status = navidromeRepository.spotifyStatus()
            val running = status.progress?.state == "running"
            _ui.update {
                it.copy(
                    phase = when {
                        status.linked && running -> Phase.IMPORTING
                        status.linked -> Phase.LINKED
                        !status.configured -> Phase.UNCONFIGURED
                        else -> Phase.NOT_LINKED
                    },
                    accountName = status.accountName,
                    progress = status.progress,
                    message = ""
                )
            }
            if (status.linked && running) startImportPolling()
            else if (status.linked) loadPreview()
        }
    }

    /** Fetch what's available to import and move to the selection screen. */
    fun loadPreview() {
        _ui.update { it.copy(phase = Phase.LINKED, busy = true, message = "") }
        viewModelScope.launch {
            val preview = navidromeRepository.spotifyPreview()
            if (preview == null) {
                _ui.update {
                    it.copy(phase = Phase.LINKED, busy = false,
                        message = "Couldn't load your Spotify library. Tap to try again.")
                }
                return@launch
            }
            _ui.update {
                it.copy(
                    phase = Phase.SELECTING,
                    busy = false,
                    preview = preview,
                    // Only pre-select playlists we can actually read.
                    selectedPlaylistIds =
                        if (preview.playlistsAvailable) preview.playlists.map { p -> p.id }.toSet()
                        else emptySet(),
                    likedSelected = preview.likedCount > 0,
                    artistsSelected = preview.topArtistsCount > 0,
                    historySelected = true,
                    message = ""
                )
            }
        }
    }

    fun togglePlaylist(id: String) {
        _ui.update {
            val sel = it.selectedPlaylistIds.toMutableSet()
            if (!sel.add(id)) sel.remove(id)
            it.copy(selectedPlaylistIds = sel)
        }
    }

    fun setAllPlaylists(selected: Boolean) {
        _ui.update {
            it.copy(selectedPlaylistIds =
                if (selected) it.preview?.playlists?.map { p -> p.id }?.toSet() ?: emptySet()
                else emptySet())
        }
    }

    fun toggleLiked() = _ui.update { it.copy(likedSelected = !it.likedSelected) }
    fun toggleArtists() = _ui.update { it.copy(artistsSelected = !it.artistsSelected) }
    fun toggleHistory() = _ui.update { it.copy(historySelected = !it.historySelected) }

    /** Begin OAuth: fetch the consent URL, open it, and poll until the gateway stores the token. */
    fun startLink() {
        if (_ui.value.busy) return
        linkPollJob?.cancel()
        _ui.update { it.copy(busy = true, message = "") }
        viewModelScope.launch {
            val link = navidromeRepository.spotifyStartLink()
            when (link.status) {
                "pending" -> {
                    _ui.update { it.copy(phase = Phase.AWAITING_APPROVAL, busy = false) }
                    if (link.authUrl.isNotBlank()) _openUrl.tryEmit(link.authUrl)
                    startLinkPolling()
                }
                "unconfigured" -> _ui.update { it.copy(phase = Phase.UNCONFIGURED, busy = false) }
                else -> _ui.update {
                    it.copy(phase = Phase.ERROR, busy = false,
                        message = "Couldn't start Spotify sign-in.")
                }
            }
        }
    }

    private fun startLinkPolling() {
        linkPollJob = viewModelScope.launch {
            // OAuth consent should take under a couple of minutes; give up well before that.
            val deadline = System.currentTimeMillis() + 5 * 60_000L
            while (System.currentTimeMillis() < deadline) {
                delay(2500)
                when (navidromeRepository.spotifyPollLink()) {
                    "linked" -> {
                        val status = navidromeRepository.spotifyStatus()
                        _ui.update {
                            it.copy(phase = Phase.LINKED, accountName = status.accountName)
                        }
                        return@launch
                    }
                    "unconfigured" -> {
                        _ui.update { it.copy(phase = Phase.UNCONFIGURED) }
                        return@launch
                    }
                }
            }
            if (_ui.value.phase == Phase.AWAITING_APPROVAL) {
                _ui.update {
                    it.copy(phase = Phase.NOT_LINKED,
                        message = "Sign-in timed out — tap Connect to try again.")
                }
            }
        }
    }

    /** Re-open the consent page if the browser was dismissed. */
    fun reopenConsent() {
        viewModelScope.launch {
            val link = navidromeRepository.spotifyStartLink()
            if (link.status == "pending" && link.authUrl.isNotBlank()) {
                _openUrl.tryEmit(link.authUrl)
            }
        }
    }

    fun startImport() {
        if (_ui.value.busy) return
        val s = _ui.value
        if (!s.hasSelection) return
        val opts = SpotifyImportOptions(
            playlistIds = s.selectedPlaylistIds.toList(),
            liked = s.likedSelected,
            artists = s.artistsSelected,
            history = s.historySelected
        )
        _ui.update { it.copy(busy = true, message = "") }
        viewModelScope.launch {
            val progress = navidromeRepository.spotifyStartImport(opts)
            if (progress == null) {
                // Couldn't reach the gateway / not linked — don't pretend an import is running.
                _ui.update {
                    it.copy(phase = Phase.ERROR, busy = false,
                        message = "Couldn't start the import. Check your connection and try again.")
                }
                return@launch
            }
            _ui.update { it.copy(phase = Phase.IMPORTING, busy = false, progress = progress) }
            startImportPolling()
        }
    }

    private fun startImportPolling() {
        importPollJob?.cancel()
        importPollJob = viewModelScope.launch {
            // Bounded so a stuck/never-terminating server state can't poll forever. A big library
            // matches in well under this; on the deadline we just re-sync the real state.
            val deadline = System.currentTimeMillis() + 45 * 60_000L
            while (System.currentTimeMillis() < deadline) {
                delay(2000)
                val p = navidromeRepository.spotifyImportStatus() ?: continue
                _ui.update { it.copy(progress = p) }
                when (p.state) {
                    "done" -> {
                        _ui.update { it.copy(phase = Phase.DONE) }
                        // Pull the new playlists in immediately, then kick a full library sync so
                        // the imported artists + taste-driven home mixes refresh too (not just
                        // playlists — that was the gap where "nothing but the playlist" showed up).
                        runCatching { navidromeRepository.syncPlaylists() }
                        runCatching {
                            WorkManager.getInstance(context).enqueue(SyncWorker.fullSyncWork())
                        }
                        return@launch
                    }
                    "error" -> {
                        _ui.update {
                            it.copy(phase = Phase.ERROR,
                                message = p.message.ifBlank { "Import failed." })
                        }
                        return@launch
                    }
                    // "idle" means nothing is running server-side (e.g. the service restarted and
                    // lost the in-memory job) — stop polling and re-read the truth.
                    "idle" -> {
                        refresh()
                        return@launch
                    }
                }
            }
            // Timed out waiting — re-sync rather than spin or falsely error.
            refresh()
        }
    }

    fun unlink() {
        if (_ui.value.busy) return
        linkPollJob?.cancel()
        importPollJob?.cancel()
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            navidromeRepository.spotifyUnlink()
            _ui.update {
                UiState(phase = Phase.NOT_LINKED)
            }
        }
    }

    override fun onCleared() {
        linkPollJob?.cancel()
        importPollJob?.cancel()
        super.onCleared()
    }
}
