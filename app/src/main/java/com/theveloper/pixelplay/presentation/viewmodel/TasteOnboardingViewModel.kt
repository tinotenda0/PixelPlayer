package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.model.Artist
import com.theveloper.pixelplay.data.navidrome.NavidromeRepository
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the pairwise "who do you prefer?" taste onboarding. Round 1 shows two artists from the
 * server's starter pool; each pick branches the next pair off the chosen artist's related artists.
 * Chosen artists (plus any custom-added) are saved as the user's seeds so the server can curate
 * their home from day one.
 */
@HiltViewModel
class TasteOnboardingViewModel @Inject constructor(
    private val navidromeRepository: NavidromeRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    data class UiState(
        val left: Artist? = null,
        val right: Artist? = null,
        val chosen: List<Artist> = emptyList(),
        val round: Int = 0,
        val totalRounds: Int = 6,
        val loading: Boolean = true,
        val finished: Boolean = false,
        val saving: Boolean = false,
        val customQuery: String = "",
        val customResults: List<Artist> = emptyList(),
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val pool = ArrayDeque<Artist>()      // upcoming candidates (front = next)
    private val shownKeys = HashSet<String>()    // artists already shown as a card
    private val chosenKeys = HashSet<String>()   // artists actually picked/added
    private var advancing = false                // guards double-taps during the related fetch

    init { start() }

    private fun key(a: Artist): String = a.navidromeId ?: a.name.lowercase()

    fun start() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            val artists = navidromeRepository.tasteStartArtists().getOrDefault(emptyList())
            pool.clear(); shownKeys.clear(); chosenKeys.clear()
            pool.addAll(artists.shuffled())
            nextPair()
        }
    }

    private fun takeFresh(): Artist? {
        while (pool.isNotEmpty()) {
            val a = pool.removeFirst()
            if (shownKeys.add(key(a))) return a
        }
        return null
    }

    private fun nextPair() {
        val l = takeFresh()
        val r = takeFresh()
        if (l == null || r == null) {
            if (_ui.value.chosen.isEmpty()) {
                // We never got any candidates at all (offline / auth failure). Dismiss WITHOUT
                // marking onboarding done, so the user still gets it on a later launch.
                _ui.update { it.copy(loading = false, finished = true) }
            } else {
                finish()
            }
            return
        }
        _ui.update { it.copy(left = l, right = r, loading = false) }
    }

    fun choose(artist: Artist) {
        if (advancing || _ui.value.saving) return // ignore extra taps while the next pair loads
        advancing = true
        val round = _ui.value.round + 1
        chosenKeys.add(key(artist))
        _ui.update { it.copy(chosen = it.chosen + artist, round = round, loading = true) }
        if (round >= _ui.value.totalRounds) {
            advancing = false
            finish()
            return
        }
        viewModelScope.launch {
            try {
                val gid = artist.navidromeId
                val related = if (gid != null) {
                    navidromeRepository.relatedArtists(gid).getOrDefault(emptyList())
                } else emptyList()
                // Prepend related artists so the next pair branches off this pick.
                related.reversed().forEach { pool.addFirst(it) }
                nextPair()
            } finally {
                advancing = false
            }
        }
    }

    fun updateCustomQuery(query: String) {
        _ui.update { it.copy(customQuery = query) }
        if (query.isBlank()) {
            _ui.update { it.copy(customResults = emptyList()) }
            return
        }
        viewModelScope.launch {
            val res = navidromeRepository.searchArtists(query, limit = 8).getOrDefault(emptyList())
            if (_ui.value.customQuery == query) _ui.update { it.copy(customResults = res) }
        }
    }

    fun addCustom(artist: Artist) {
        // Dedup against what was actually CHOSEN — an artist merely shown as a card earlier must
        // still be addable here. Always clear the query so the tap is never a silent no-op.
        val isNew = chosenKeys.add(key(artist))
        shownKeys.add(key(artist))
        _ui.update {
            it.copy(
                chosen = if (isNew) it.chosen + artist else it.chosen,
                customQuery = "",
                customResults = emptyList()
            )
        }
    }

    fun finish() {
        if (_ui.value.saving) return
        val chosen = _ui.value.chosen
        _ui.update { it.copy(saving = true) }
        viewModelScope.launch {
            // Only mark onboarding done if the seeds actually saved — otherwise a failed save would
            // silently lose the user's picks forever and never re-prompt.
            val saved = if (chosen.isNotEmpty()) {
                navidromeRepository.setTasteSeeds(chosen.map { it.name }).isSuccess
            } else true
            if (saved) userPreferencesRepository.setTasteOnboardingDone(true)
            _ui.update { it.copy(finished = true, saving = false) }
        }
    }

    /** Dismiss without saving; still mark done so it doesn't nag on every launch. */
    fun skip() {
        viewModelScope.launch {
            userPreferencesRepository.setTasteOnboardingDone(true)
            _ui.update { it.copy(finished = true) }
        }
    }
}
