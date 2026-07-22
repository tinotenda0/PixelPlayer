package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
 * Drives OAuth device-code linking of a YouTube Music account.
 *
 * The user never types credentials here: the server hands back a short code, the user approves it
 * at google.com/device, and we poll until it's accepted. Linking only affects metadata
 * (home/library/likes/history) — audio is always fetched anonymously.
 */
@HiltViewModel
class YtMusicLinkViewModel @Inject constructor(
    private val navidromeRepository: NavidromeRepository
) : ViewModel() {

    enum class Phase { LOADING, UNCONFIGURED, NOT_LINKED, AWAITING_APPROVAL, LINKED, ERROR }

    data class UiState(
        val phase: Phase = Phase.LOADING,
        val userCode: String = "",
        val verificationUrl: String = "https://google.com/device",
        val busy: Boolean = false
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var pollJob: Job? = null

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(phase = Phase.LOADING) }
            val status = navidromeRepository.ytmStatus()
            _ui.update {
                it.copy(
                    phase = when {
                        status.linked -> Phase.LINKED
                        !status.configured -> Phase.UNCONFIGURED
                        else -> Phase.NOT_LINKED
                    }
                )
            }
        }
    }

    fun startLink() {
        if (_ui.value.busy) return
        pollJob?.cancel()
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val link = navidromeRepository.ytmStartLink()
            when (link.status) {
                "pending" -> {
                    _ui.update {
                        it.copy(
                            phase = Phase.AWAITING_APPROVAL,
                            userCode = link.userCode,
                            verificationUrl = link.verificationUrl,
                            busy = false
                        )
                    }
                    startPolling(link.intervalSeconds)
                }
                "unconfigured" -> _ui.update { it.copy(phase = Phase.UNCONFIGURED, busy = false) }
                else -> _ui.update { it.copy(phase = Phase.ERROR, busy = false) }
            }
        }
    }

    private fun startPolling(intervalSeconds: Int) {
        pollJob = viewModelScope.launch {
            // Device codes expire after ~30 min; stop well before that rather than poll forever.
            val deadline = System.currentTimeMillis() + 15 * 60_000L
            while (System.currentTimeMillis() < deadline) {
                delay(intervalSeconds.coerceAtLeast(2) * 1000L)
                when (navidromeRepository.ytmPollLink()) {
                    "linked" -> {
                        _ui.update { it.copy(phase = Phase.LINKED, userCode = "") }
                        return@launch
                    }
                    "unconfigured" -> {
                        _ui.update { it.copy(phase = Phase.UNCONFIGURED) }
                        return@launch
                    }
                    "none" -> return@launch
                }
            }
            if (_ui.value.phase == Phase.AWAITING_APPROVAL) {
                _ui.update { it.copy(phase = Phase.NOT_LINKED, userCode = "") }
            }
        }
    }

    fun cancelLink() {
        pollJob?.cancel()
        _ui.update { it.copy(phase = Phase.NOT_LINKED, userCode = "", busy = false) }
    }

    fun unlink() {
        if (_ui.value.busy) return
        pollJob?.cancel()
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            navidromeRepository.ytmUnlink()
            _ui.update { it.copy(phase = Phase.NOT_LINKED, userCode = "", busy = false) }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}
