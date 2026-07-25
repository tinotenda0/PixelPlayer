package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.jam.JamManager
import com.theveloper.pixelplay.data.navidrome.JamHost
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the Jam screen (household remote control). Discovers other household phones currently
 * playing and controls the one the user picks; also owns the host-side "allow control" toggle.
 */
@HiltViewModel
class JamViewModel @Inject constructor(
    private val jamManager: JamManager,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    /** Household devices currently playing (excludes this one). */
    val hosts: StateFlow<List<JamHost>> = jamManager.hosts

    /** Host opt-in: whether other household phones may control this device. */
    val allowControl: StateFlow<Boolean> =
        userPreferencesRepository.allowHouseholdControlFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    init { jamManager.startDiscovery() }

    fun setAllowControl(enabled: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setAllowHouseholdControl(enabled) }
    }

    fun select(id: String) { _selectedId.value = id }
    fun clearSelection() { _selectedId.value = null }

    fun playPause() = sendToSelected("playpause")
    fun next() = sendToSelected("next")
    fun previous() = sendToSelected("previous")
    fun seek(positionMs: Long) {
        val id = _selectedId.value ?: return
        viewModelScope.launch { jamManager.control(id, "seek", positionMs) }
    }

    private fun sendToSelected(action: String) {
        val id = _selectedId.value ?: return
        viewModelScope.launch { jamManager.control(id, action) }
    }

    override fun onCleared() {
        jamManager.stopDiscovery()
        super.onCleared()
    }
}
