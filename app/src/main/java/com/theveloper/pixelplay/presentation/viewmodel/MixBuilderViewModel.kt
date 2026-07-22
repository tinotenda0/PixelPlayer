package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.model.Artist
import com.theveloper.pixelplay.data.navidrome.NavidromeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State for the custom mix builder: the user picks a handful of artists and the gateway blends
 * them into a playlist, which it saves so every client (and every household member) can see it.
 */
data class MixBuilderUiState(
    val query: String = "",
    val suggestions: List<Artist> = emptyList(),
    val picked: List<Artist> = emptyList(),
    val mixName: String = "",
    val isSearching: Boolean = false,
    val isBuilding: Boolean = false,
    val error: String? = null,
    /** Set once the gateway has saved the playlist; the screen navigates to it. */
    val builtPlaylistId: String? = null,
    val builtSongCount: Int = 0
) {
    val canBuild: Boolean get() = picked.isNotEmpty() && !isBuilding
}

@HiltViewModel
class MixBuilderViewModel @Inject constructor(
    private val navidromeRepository: NavidromeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MixBuilderUiState())
    val uiState: StateFlow<MixBuilderUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(suggestions = emptyList(), isSearching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(280) // debounce typing
            _uiState.update { it.copy(isSearching = true) }
            val results = navidromeRepository.searchArtists(query, limit = 12).getOrNull().orEmpty()
            // A stale response must not overwrite a newer query's suggestions.
            _uiState.update { state ->
                if (state.query != query) state
                else state.copy(suggestions = results, isSearching = false)
            }
        }
    }

    fun pick(artist: Artist) {
        // Only gateway artists can seed a mix — a local-only artist has no upstream id to blend.
        if (artist.navidromeId.isNullOrBlank()) return
        _uiState.update { state ->
            if (state.picked.any { it.navidromeId == artist.navidromeId }) state
            else state.copy(
                picked = state.picked + artist,
                query = "",
                suggestions = emptyList()
            )
        }
    }

    fun unpick(artist: Artist) {
        _uiState.update { state ->
            state.copy(picked = state.picked.filterNot { it.navidromeId == artist.navidromeId })
        }
    }

    fun onNameChange(name: String) {
        _uiState.update { it.copy(mixName = name) }
    }

    fun build() {
        val state = _uiState.value
        if (!state.canBuild) return
        val ids = state.picked.mapNotNull { it.navidromeId }
        if (ids.isEmpty()) return
        val name = state.mixName.ifBlank { defaultName(state.picked) }

        _uiState.update { it.copy(isBuilding = true, error = null) }
        viewModelScope.launch {
            navidromeRepository.buildMix(name = name, artistIds = ids).fold(
                onSuccess = { mix ->
                    _uiState.update {
                        it.copy(
                            isBuilding = false,
                            builtPlaylistId = mix.playlistId,
                            builtSongCount = mix.songCount
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isBuilding = false, error = e.message ?: "Couldn't build that mix")
                    }
                }
            )
        }
    }

    /** "Burna Boy Mix" for one artist, "Burna Boy & Wizkid Mix" for two, "… Mix" beyond that. */
    private fun defaultName(picked: List<Artist>): String = when (picked.size) {
        1 -> "${picked[0].name} Mix"
        2 -> "${picked[0].name} & ${picked[1].name} Mix"
        else -> "${picked[0].name} & ${picked.size - 1} more Mix"
    }

    fun consumeNavigation() {
        _uiState.update { it.copy(builtPlaylistId = null) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
}
