package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.navidrome.NavidromeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AccountsUiState(
    val isConnected: Boolean = false,
    val username: String? = null,
    val syncedPlaylistsLabel: String? = null,
    val isLoggingOut: Boolean = false
)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val navidromeRepository: NavidromeRepository
) : ViewModel() {

    private val isLoggingOut = MutableStateFlow(false)

    val uiState: StateFlow<AccountsUiState> = combine(
        navidromeRepository.isLoggedInFlow,
        navidromeRepository.getPlaylists().map { it.size },
        isLoggingOut
    ) { connected, playlistCount, loggingOut ->
        AccountsUiState(
            isConnected = connected,
            username = navidromeRepository.username?.takeIf { it.isNotBlank() },
            syncedPlaylistsLabel = if (connected) {
                formatCount(playlistCount, "synced playlist", "synced playlists")
            } else {
                null
            },
            isLoggingOut = loggingOut
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    fun logout() {
        if (isLoggingOut.value) return

        viewModelScope.launch {
            isLoggingOut.value = true
            try {
                navidromeRepository.logout()
            } finally {
                isLoggingOut.value = false
            }
        }
    }

    private fun formatCount(count: Int, singular: String, plural: String): String {
        return if (count == 1) {
            "1 $singular"
        } else {
            "$count $plural"
        }
    }
}
