package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.model.Album
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.repository.MusicRepository // Importar MusicRepository
import com.theveloper.pixelplay.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlbumDetailUiState(
    val album: Album? = null,
    val songs: List<Song> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val navidromeRepository: com.theveloper.pixelplay.data.navidrome.NavidromeRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumDetailUiState())
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()

    init {
        val albumIdString: String? = savedStateHandle.get("albumId")
        if (albumIdString != null) {
            val albumId = albumIdString.toLongOrNull()
            when {
                albumId != null -> loadAlbumData(albumId)
                albumIdString.startsWith("yt-") -> loadGatewayAlbum(albumIdString)
                else -> _uiState.update { it.copy(error = context.getString(R.string.album_detail_invalid_id), isLoading = false) }
            }
        } else {
            _uiState.update { it.copy(error = context.getString(R.string.album_detail_id_not_found), isLoading = false) }
        }
    }

    /**
     * Load a gateway (Subsonic/YouTube) album on demand — its metadata + full track list — when
     * the album id is a `yt-album-…` string rather than a local Room Long id.
     */
    private fun loadGatewayAlbum(gatewayId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val pair = navidromeRepository.getAlbumDetail(gatewayId).getOrNull()
            if (pair == null) {
                _uiState.update {
                    it.copy(error = context.getString(R.string.album_detail_not_found), isLoading = false)
                }
                return@launch
            }
            val (album, songs) = pair
            _uiState.value = AlbumDetailUiState(album = album, songs = songs, isLoading = false)
        }
    }

    private fun loadAlbumData(id: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val albumDetailsFlow = musicRepository.getAlbumById(id)
                val albumSongsFlow = musicRepository.getSongsForAlbum(id)

                combine(albumDetailsFlow, albumSongsFlow) { album, songs ->
                    if (album != null) {
                        AlbumDetailUiState(
                            album = album,
                            songs = songs.sortedWith(
                                compareBy<Song> { it.discNumber ?: 1 }
                                    .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                                    .thenBy { it.title.lowercase() }
                            ),
                            isLoading = false
                        )
                    } else {
                        AlbumDetailUiState(
                            error = context.getString(R.string.album_detail_not_found),
                            isLoading = false
                        )
                    }
                }
                    .catch { e ->
                        emit(
                            AlbumDetailUiState(
                                error = context.getString(R.string.album_detail_error_loading_album, e.localizedMessage ?: ""),
                                isLoading = false
                            )
                        )
                    }
                    .collect { newState ->
                        _uiState.value = newState
                    }

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error = context.getString(R.string.album_detail_error_loading_album, e.localizedMessage ?: ""),
                        isLoading = false
                    )
                }
            }
        }
    }

    fun update(songs: List<Song>) {
        _uiState.update {
            it.copy(
                isLoading = false,
                songs = songs
            )
        }
    }
}
