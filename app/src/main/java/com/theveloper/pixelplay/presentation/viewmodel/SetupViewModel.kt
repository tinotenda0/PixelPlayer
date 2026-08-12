package com.theveloper.pixelplay.presentation.viewmodel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.backup.BackupManager
import com.theveloper.pixelplay.data.backup.model.BackupTransferProgressUpdate
import com.theveloper.pixelplay.data.backup.model.BackupOperationType
import com.theveloper.pixelplay.data.backup.model.BackupSection
import com.theveloper.pixelplay.data.backup.model.RestorePlan
import com.theveloper.pixelplay.data.backup.model.RestoreResult
import com.theveloper.pixelplay.data.navidrome.NavidromeRepository
import com.theveloper.pixelplay.data.preferences.AppThemeMode
import com.theveloper.pixelplay.data.preferences.ThemePreferencesRepository
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.worker.SyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SetupUiState(
    val notificationsPermissionGranted: Boolean = false,
    val libraryNavigationMode: String = "tab_row",
    val navBarStyle: String = "default",
    val navBarCornerRadius: Int = 28,
    val alarmsPermissionGranted: Boolean = false,
    val appThemeMode: String = AppThemeMode.DARK,
    val isInspectingBackup: Boolean = false,
    val isRestoringBackup: Boolean = false,
    val restorePlan: RestorePlan? = null,
    val backupTransferProgress: BackupTransferProgressUpdate? = null,
    val isGatewayConnected: Boolean = false,
    val isSigningIntoGateway: Boolean = false,
    val gatewaySignInError: String? = null
) {
    val allPermissionsGranted: Boolean
        get() {
            // Local media browsing is retired — the library is server-backed, so no storage
            // permission is required. Only the playback-notification permission matters (13+).
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationsPermissionGranted else true
        }
}

sealed interface SetupEvent {
    data class Message(val value: String) : SetupEvent
    data class RestoreCompleted(val message: String) : SetupEvent
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val themePreferencesRepository: ThemePreferencesRepository,
    private val syncManager: SyncManager,
    private val backupManager: BackupManager,
    private val navidromeRepository: NavidromeRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<SetupEvent>()
    val events = _events.asSharedFlow()
    
    /**
     * Expose sync progress for UI to show during initial setup
     */
    val isSyncing = syncManager.isSyncing

    init {
        viewModelScope.launch {
            if (!userPreferencesRepository.initialSetupDoneFlow.first()) {
                themePreferencesRepository.initializeAppThemeMode(AppThemeMode.DARK)
            }
        }

        // Consolidated collectors using combine() to reduce coroutine overhead
        viewModelScope.launch {
            combine(
                userPreferencesRepository.libraryNavigationModeFlow,
                userPreferencesRepository.navBarStyleFlow,
                userPreferencesRepository.navBarCornerRadiusFlow,
                themePreferencesRepository.appThemeModeFlow
            ) { mode, style, radius, appThemeMode ->
                SetupPrefsUpdate(mode, style, radius, appThemeMode)
            }.collect { update ->
                _uiState.update { state ->
                    state.copy(
                        libraryNavigationMode = update.mode,
                        navBarStyle = update.style,
                        navBarCornerRadius = update.radius,
                        appThemeMode = update.appThemeMode
                    )
                }
            }
        }

        viewModelScope.launch {
            navidromeRepository.isLoggedInFlow.collect { connected ->
                _uiState.update { it.copy(isGatewayConnected = connected) }
            }
        }
    }

    fun signInToGateway(username: String, password: String) {
        if (_uiState.value.isSigningIntoGateway) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSigningIntoGateway = true, gatewaySignInError = null) }
            val result = navidromeRepository.login(NavidromeRepository.GATEWAY_URL, username, password)
            _uiState.update {
                it.copy(
                    isSigningIntoGateway = false,
                    gatewaySignInError = result.exceptionOrNull()?.message
                )
            }
        }
    }

    fun clearGatewaySignInError() {
        _uiState.update { it.copy(gatewaySignInError = null) }
    }

    private data class SetupPrefsUpdate(
        val mode: String,
        val style: String,
        val radius: Int,
        val appThemeMode: String
    )

    fun checkPermissions(context: Context) {
        val notificationsPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required before Android 13 (Tiramisu)
        }

        val alarmsPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        _uiState.update {
            it.copy(
                notificationsPermissionGranted = notificationsPermissionGranted,
                alarmsPermissionGranted = alarmsPermissionGranted
            )
        }
    }

    fun setLibraryNavigationMode(mode: String) {
        viewModelScope.launch {
            userPreferencesRepository.setLibraryNavigationMode(mode)
        }
    }

    fun setNavBarStyle(style: String) {
        viewModelScope.launch {
            userPreferencesRepository.setNavBarStyle(style)
        }
    }

    fun setNavBarCornerRadius(radius: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setNavBarCornerRadius(radius)
        }
    }

    fun setAppThemeMode(mode: String) {
        viewModelScope.launch {
            themePreferencesRepository.setAppThemeMode(mode)
        }
    }

    fun setSetupComplete() {
        viewModelScope.launch {
            completeSetup(syncAfter = true)
        }
    }
    
    /**
     * Retry the initial sync if it failed.
     * Can be called from UI when user wants to retry after a failure.
     */
    fun retrySync() {
        viewModelScope.launch {
            syncManager.forceRefresh()
        }
    }

    fun inspectBackupFile(uri: Uri) {
        if (_uiState.value.isInspectingBackup || _uiState.value.isRestoringBackup) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isInspectingBackup = true,
                    restorePlan = null,
                    backupTransferProgress = null
                )
            }
            val result = backupManager.inspectBackup(uri)
            result.fold(
                onSuccess = { plan ->
                    _uiState.update {
                        it.copy(
                            isInspectingBackup = false,
                            restorePlan = plan
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isInspectingBackup = false) }
                    _events.emit(
                        SetupEvent.Message(
                            context.getString(
                                R.string.settings_backup_invalid_format,
                                error.localizedMessage ?: context.getString(R.string.common_error_unknown),
                            )
                        )
                    )
                }
            )
        }
    }

    fun updateRestorePlanSelection(selectedModules: Set<BackupSection>) {
        _uiState.update { state ->
            state.restorePlan?.let { plan ->
                state.copy(restorePlan = plan.copy(selectedModules = selectedModules))
            } ?: state
        }
    }

    fun clearRestorePlan() {
        _uiState.update {
            it.copy(
                restorePlan = null,
                isInspectingBackup = false,
                isRestoringBackup = false,
                backupTransferProgress = null
            )
        }
    }

    fun restoreFromPlan(uri: Uri) {
        val plan = _uiState.value.restorePlan ?: return
        if (plan.selectedModules.isEmpty() || _uiState.value.isRestoringBackup) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRestoringBackup = true,
                    backupTransferProgress = BackupTransferProgressUpdate(
                        operation = BackupOperationType.IMPORT,
                        step = 0,
                        totalSteps = 1,
                        title = context.getString(R.string.settings_backup_progress_preparing_restore),
                        detail = context.getString(R.string.settings_backup_progress_starting_task),
                    )
                )
            }

            val result = backupManager.restore(uri, plan) { progress ->
                _uiState.update { state -> state.copy(backupTransferProgress = progress) }
            }

            when (result) {
                is RestoreResult.Success -> {
                    _events.emit(SetupEvent.RestoreCompleted(context.getString(R.string.settings_restore_completed_success)))
                }
                is RestoreResult.PartialFailure -> {
                    val canFinishSetup = result.succeeded.isNotEmpty() || !result.rolledBack
                    if (canFinishSetup) {
                        _events.emit(
                            SetupEvent.RestoreCompleted(
                                context.getString(R.string.settings_restore_completed_partial_issues),
                            )
                        )
                    } else {
                        _events.emit(
                            SetupEvent.Message(
                                context.getString(
                                    R.string.settings_restore_could_not_complete,
                                    result.failed.values.joinToString(),
                                ),
                            )
                        )
                    }
                }
                is RestoreResult.TotalFailure -> {
                    _events.emit(SetupEvent.Message(context.getString(R.string.settings_restore_failed_format, result.error)))
                }
            }

            _uiState.update {
                it.copy(
                    isRestoringBackup = false,
                    restorePlan = null,
                    backupTransferProgress = null
                )
            }
        }
    }

    private suspend fun completeSetup(syncAfter: Boolean) {
        userPreferencesRepository.setInitialSetupDone(true)
        if (syncAfter) {
            syncManager.forceRefresh()
        }
    }
}
