package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.jam.JamManager
import com.theveloper.pixelplay.data.navidrome.DeviceSession
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
 * Backs the Devices screen: household remote control (Jam) and personal handoff between this
 * account's own devices. Discovers both kinds of device and controls whichever the user picks;
 * also owns the host-side "allow household control" toggle, which only gates Jam discoverability
 * - personal handoff always works between your own devices.
 */
@HiltViewModel
class JamViewModel @Inject constructor(
    private val jamManager: JamManager,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    /** Household devices currently playing (excludes this one). */
    val hosts: StateFlow<List<JamHost>> = jamManager.hosts

    /** This account's other live devices, playing or idle (excludes this one). */
    val devices: StateFlow<List<DeviceSession>> = jamManager.devices

    /** Host opt-in: whether other household phones may control this device. */
    val allowControl: StateFlow<Boolean> =
        userPreferencesRepository.allowHouseholdControlFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    private val _selectedDeviceId = MutableStateFlow<String?>(null)
    val selectedDeviceId: StateFlow<String?> = _selectedDeviceId.asStateFlow()

    init {
        jamManager.startDiscovery()
        jamManager.startDeviceDiscovery()
    }

    fun setAllowControl(enabled: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setAllowHouseholdControl(enabled) }
    }

    fun select(id: String) { _selectedDeviceId.value = null; _selectedId.value = id }
    fun clearSelection() { _selectedId.value = null }

    fun selectDevice(id: String) { _selectedId.value = null; _selectedDeviceId.value = id }
    fun clearDeviceSelection() { _selectedDeviceId.value = null }

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

    fun playPauseDevice() = sendToSelectedDevice("playpause")
    fun nextDevice() = sendToSelectedDevice("next")
    fun previousDevice() = sendToSelectedDevice("previous")
    fun seekDevice(positionMs: Long) {
        val id = _selectedDeviceId.value ?: return
        viewModelScope.launch { jamManager.controlDevice(id, "seek", positionMs) }
    }

    private fun sendToSelectedDevice(action: String) {
        val id = _selectedDeviceId.value ?: return
        viewModelScope.launch { jamManager.controlDevice(id, action) }
    }

    /** Pull [deviceId]'s queue+position into this device and resume here. */
    fun playHere(deviceId: String) {
        viewModelScope.launch { jamManager.pullFrom(deviceId) }
    }

    /** Push this device's queue+position to [deviceId] and pause here. */
    fun sendTo(deviceId: String) {
        viewModelScope.launch { jamManager.transferTo(deviceId) }
    }

    override fun onCleared() {
        jamManager.stopDiscovery()
        jamManager.stopDeviceDiscovery()
        super.onCleared()
    }
}
