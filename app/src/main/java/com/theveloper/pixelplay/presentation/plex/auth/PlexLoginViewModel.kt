package com.theveloper.pixelplay.presentation.plex.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.network.plex.PlexApiService
import com.theveloper.pixelplay.data.plex.PlexRepository
import com.theveloper.pixelplay.data.plex.model.PlexAccount
import com.theveloper.pixelplay.data.plex.model.PlexHomeUser
import com.theveloper.pixelplay.data.plex.model.PlexServerConnection
import com.theveloper.pixelplay.data.plex.model.PlexServerResource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

sealed interface PlexLoginState {
    data object Idle : PlexLoginState
    data object Loading : PlexLoginState
    data class Success(val username: String) : PlexLoginState
    data class Error(val message: String) : PlexLoginState

    /** Web auth: waiting for the user to approve the PIN in the browser. */
    data class AwaitingApproval(val code: String, val authUrl: String) : PlexLoginState

    /** Web auth: multiple Plex Home users — pick who's listening. */
    data class SelectHomeUser(val users: List<PlexHomeUser>) : PlexLoginState

    /** Web auth: more than one reachable server on the account. */
    data class SelectServer(val servers: List<ResolvedServer>) : PlexLoginState
}

/** A server with its best reachable address already resolved. */
data class ResolvedServer(
    val name: String,
    val clientIdentifier: String,
    val uri: String,
    val accessToken: String?
)

@HiltViewModel
class PlexLoginViewModel @Inject constructor(
    private val repository: PlexRepository,
    private val api: PlexApiService
) : ViewModel() {

    private val _state = MutableStateFlow<PlexLoginState>(PlexLoginState.Idle)
    val state: StateFlow<PlexLoginState> = _state.asStateFlow()

    /** Fired once when the browser should be opened for approval. */
    private val _openUrlEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openUrlEvents: SharedFlow<String> = _openUrlEvents.asSharedFlow()

    private var pollJob: Job? = null

    // Web-auth session context carried between steps.
    private var accountUuid: String = ""
    private var accountUsername: String = ""
    private var plexTvToken: String = ""
    private var userToken: String = ""

    // ─── Web auth (default) ────────────────────────────────────────────────

    fun startWebAuth() {
        if (_state.value is PlexLoginState.Loading ||
            _state.value is PlexLoginState.AwaitingApproval
        ) return

        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            _state.value = PlexLoginState.Loading

            val (pinId, code) = api.createAuthPin().getOrElse {
                _state.value = PlexLoginState.Error(it.message ?: "Could not reach plex.tv")
                return@launch
            }

            val authUrl = api.buildAuthUrl(code)
            _state.value = PlexLoginState.AwaitingApproval(code, authUrl)
            _openUrlEvents.tryEmit(authUrl)

            // Poll for approval: every 3s for up to 5 minutes.
            repeat(100) {
                delay(3_000)
                val token = api.checkAuthPin(pinId).getOrNull()
                if (token != null) {
                    onAccountTokenObtained(token)
                    return@launch
                }
            }
            _state.value = PlexLoginState.Error("Sign-in timed out. Try again.")
        }
    }

    private suspend fun onAccountTokenObtained(token: String) {
        _state.value = PlexLoginState.Loading
        plexTvToken = token

        val (uuid, name) = api.getAccountDetails(token).getOrElse {
            _state.value = PlexLoginState.Error(it.message ?: "Could not load account")
            return
        }
        accountUuid = uuid
        accountUsername = name

        val homeUsers = api.getHomeUsers(token).getOrNull().orEmpty()
        if (homeUsers.size > 1) {
            _state.value = PlexLoginState.SelectHomeUser(homeUsers)
        } else {
            userToken = token
            discoverServers()
        }
    }

    /**
     * Home user picked. The admin/owner keeps the account token; others get a
     * user-scoped token via the switch endpoint ([pin] for protected users).
     */
    fun selectHomeUser(user: PlexHomeUser, pin: String? = null) {
        viewModelScope.launch {
            _state.value = PlexLoginState.Loading

            if (user.isAdmin || user.uuid == accountUuid) {
                userToken = plexTvToken
                accountUsername = user.title
                discoverServers()
                return@launch
            }

            api.switchHomeUser(plexTvToken, user.uuid, pin).fold(
                onSuccess = { token ->
                    userToken = token
                    accountUuid = user.uuid
                    accountUsername = user.title
                    discoverServers()
                },
                onFailure = { error ->
                    _state.value = PlexLoginState.Error(error.message ?: "User switch failed")
                }
            )
        }
    }

    private suspend fun discoverServers() {
        val resources = api.getServers(userToken).getOrElse {
            _state.value = PlexLoginState.Error(it.message ?: "Could not list servers")
            return
        }
        if (resources.isEmpty()) {
            _state.value = PlexLoginState.Error("No Plex Media Server found on this account")
            return
        }

        val resolved = resources.mapNotNull { resolveServer(it) }
        when {
            resolved.isEmpty() ->
                _state.value = PlexLoginState.Error(
                    "Could not reach any Plex server. Check that the server is online."
                )
            resolved.size == 1 -> finalizeAccount(resolved.first())
            else -> _state.value = PlexLoginState.SelectServer(resolved)
        }
    }

    /**
     * Probes a server's addresses and keeps the best reachable one:
     * local first, then remote, relay as a last resort.
     */
    private suspend fun resolveServer(server: PlexServerResource): ResolvedServer? {
        val token = server.accessToken ?: userToken
        val phases: List<List<PlexServerConnection>> = listOf(
            server.connections.filter { it.isLocal && !it.isRelay },
            server.connections.filter { !it.isLocal && !it.isRelay },
            server.connections.filter { it.isRelay }
        )

        for (phase in phases) {
            if (phase.isEmpty()) continue
            val probed = coroutineScope {
                phase.map { connection ->
                    async { connection to api.testServerConnection(connection.uri, token) }
                }.awaitAll()
            }
            probed.firstOrNull { it.second }?.let { (connection, _) ->
                return ResolvedServer(
                    name = server.name,
                    clientIdentifier = server.clientIdentifier,
                    uri = connection.uri,
                    accessToken = server.accessToken
                )
            }
        }
        Timber.w("PlexLoginVM: no reachable connection for server ${server.name}")
        return null
    }

    fun selectServer(server: ResolvedServer) {
        viewModelScope.launch {
            _state.value = PlexLoginState.Loading
            finalizeAccount(server)
        }
    }

    private suspend fun finalizeAccount(server: ResolvedServer) {
        val account = PlexAccount(
            id = "$accountUuid@${server.clientIdentifier}",
            username = accountUsername,
            plexTvToken = plexTvToken,
            serverToken = server.accessToken ?: userToken,
            serverUrl = server.uri,
            serverName = server.name
        )
        repository.addAccountAndActivate(account).fold(
            onSuccess = { _state.value = PlexLoginState.Success(it) },
            onFailure = {
                _state.value = PlexLoginState.Error(it.message ?: "Could not save account")
            }
        )
    }

    fun cancelWebAuth() {
        pollJob?.cancel()
        pollJob = null
        _state.value = PlexLoginState.Idle
    }

    // ─── Manual sign-in (advanced) ─────────────────────────────────────────

    fun login(serverUrl: String, username: String, password: String) {
        if (_state.value is PlexLoginState.Loading) return

        viewModelScope.launch {
            _state.value = PlexLoginState.Loading

            val result = repository.login(serverUrl, username, password)

            _state.value = result.fold(
                onSuccess = { PlexLoginState.Success(it) },
                onFailure = { PlexLoginState.Error(it.message ?: "Login failed") }
            )
        }
    }

    fun clearError() {
        if (_state.value is PlexLoginState.Error) {
            _state.value = PlexLoginState.Idle
        }
    }

    fun reset() {
        pollJob?.cancel()
        _state.value = PlexLoginState.Idle
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}
