package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.jam.JamManager
import com.theveloper.pixelplay.data.navidrome.ActiveSession
import com.theveloper.pixelplay.data.navidrome.DeviceSession
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
 * Backs the Devices screen: personal handoff between this account's own devices - one canonical
 * session, wherever it's currently active - and household remote control (Jam, control-only,
 * gated by the "allow household control" toggle this ViewModel also owns). All three lists
 * ([mySession], [devices], [householdSessions]) are kept live by [JamManager]'s push connection;
 * this ViewModel just exposes them plus UI selection state.
 */
@HiltViewModel
class JamViewModel @Inject constructor(
    private val jamManager: JamManager,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    /** This account's one canonical active session, wherever it is - null if nothing is
     *  playing anywhere on the account right now. */
    val mySession: StateFlow<ActiveSession?> = jamManager.mySession

    /** This account's other registered devices - the send-only pick list. */
    val devices: StateFlow<List<DeviceSession>> = jamManager.devices

    /** Household members' active, visible sessions. */
    val householdSessions: StateFlow<List<ActiveSession>> = jamManager.householdSessions

    /** This device's own session id - tells "mySession is already playing here" apart from
     *  "mySession is playing somewhere else, offer Play Here." */
    val mySessionId: String = jamManager.sessionId

    /** Host opt-in: whether other household phones may control this device. */
    val allowControl: StateFlow<Boolean> =
        userPreferencesRepository.allowHouseholdControlFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _selectedUser = MutableStateFlow<String?>(null)
    val selectedUser: StateFlow<String?> = _selectedUser.asStateFlow()

    private val _personalPanelOpen = MutableStateFlow(false)
    val personalPanelOpen: StateFlow<Boolean> = _personalPanelOpen.asStateFlow()

    init {
        // The device list otherwise only updates via a live "device" push (while already
        // connected) or a slow background hygiene tick — force a fresh pull the moment this
        // screen is actually opened, so a device that registered while it was closed shows up
        // immediately instead of after up to a minute.
        viewModelScope.launch { jamManager.refreshDevices() }
    }

    fun setAllowControl(enabled: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setAllowHouseholdControl(enabled) }
    }

    fun openPersonalPanel() { _selectedUser.value = null; _personalPanelOpen.value = true }
    fun closePersonalPanel() { _personalPanelOpen.value = false }

    fun selectHousehold(user: String) { _personalPanelOpen.value = false; _selectedUser.value = user }
    fun clearHouseholdSelection() { _selectedUser.value = null }

    fun playPauseSelf() { viewModelScope.launch { jamManager.controlSelf("playpause") } }
    fun nextSelf() { viewModelScope.launch { jamManager.controlSelf("next") } }
    fun previousSelf() { viewModelScope.launch { jamManager.controlSelf("previous") } }
    fun seekSelf(positionMs: Long) {
        viewModelScope.launch { jamManager.controlSelf("seek", positionMs) }
    }

    fun playPauseHousehold() = sendToSelectedHousehold("playpause")
    fun nextHousehold() = sendToSelectedHousehold("next")
    fun previousHousehold() = sendToSelectedHousehold("previous")
    fun seekHousehold(positionMs: Long) {
        val user = _selectedUser.value ?: return
        viewModelScope.launch { jamManager.controlHousehold(user, "seek", positionMs) }
    }

    private fun sendToSelectedHousehold(action: String) {
        val user = _selectedUser.value ?: return
        viewModelScope.launch { jamManager.controlHousehold(user, action) }
    }

    /** Pull this account's canonical session into this device and take over. */
    fun playHere() {
        viewModelScope.launch { jamManager.pullFrom() }
    }

    /** Push this device's current queue+position to [deviceId] and pause here. */
    fun sendTo(deviceId: String) {
        viewModelScope.launch { jamManager.transferTo(deviceId) }
    }
}
