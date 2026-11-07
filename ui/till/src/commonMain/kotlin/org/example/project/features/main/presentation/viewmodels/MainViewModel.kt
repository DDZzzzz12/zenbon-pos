package org.example.project.features.main.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.project.core.data.domain.service.PreferenceSyncService
import org.example.project.core.data.domain.service.SyncService
import org.example.project.core.data.domain.service.TerminalRegistrationService
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError

/**
 * MainViewModel for managing main application flow.
 *
 * Handles main application state management and sync operations.
 * Note: Desktop initialization is handled separately through DesktopInitializationViewModel.
 * Mobile platforms assume the system is already initialized by desktop.
 *
 * Follows MVVM pattern with reactive state management.
 */
class MainViewModel(
    private val preferenceSyncService: PreferenceSyncService,
    private val syncService: SyncService,
    private val terminalRegistrationService: TerminalRegistrationService,
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
) : ViewModel() {
    private val logger = Logger.withTag("MainViewModel")

    // Private mutable state
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    /** The running sync, retries included. A second [performSync] replaces it rather than racing it. */
    private var syncJob: Job? = null

    private fun loadTerminalId() {
        val terminalId = terminalRegistrationService.getTerminalId()
        _uiState.value = _uiState.value.copy(terminalId = terminalId)
    }

    fun onLogout() {
        syncJob?.cancel()
        syncJob = null
        _uiState.value = MainUiState()
    }

    /**
     * Dismisses the current error state.
     * Allows users to retry operations after error acknowledgment.
     */
    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Synchronizes local data with the server, retrying until it succeeds.
     *
     * The retry is what keeps a till usable across a server restart or a dropped network: the
     * sync overlay stays up while [MainUiState.syncCompleted] is false, so a single failed attempt
     * used to leave the till showing "Daten werden synchronisiert" until someone restarted the app
     * (E2E-2026-09-19). The operator sees the failure through [MainUiState.error] meanwhile.
     */
    fun performSync() {
        syncJob?.cancel()
        syncJob =
            viewModelScope.launch {
                var attempt = 0
                while (!syncOnce()) {
                    delay(retryDelaysMs[attempt.coerceAtMost(retryDelaysMs.lastIndex)])
                    attempt++
                }
            }
    }

    /** One attempt. Returns true when the till is in sync; on failure it leaves [MainUiState.error] set. */
    private suspend fun syncOnce(): Boolean {
        logger.i { "MainViewModel.performSync: starting sync" }
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        // Always sync preferences (shop info, voucher config) - uses KeyValueStore only
        var failed = false
        runCatchingApi { preferenceSyncService.syncPreferences() }
            .onSuccess {
                logger.d { "MainViewModel.performSync: preferences synced successfully" }
            }.onError { uiError ->
                failed = true
                logger.e { "MainViewModel.performSync: preference sync failed - $uiError" }
                _uiState.value = _uiState.value.copy(isLoading = false, error = uiError)
            }
        if (failed) return false

        runCatchingApi { syncService.performCompleteSync() }
            .onSuccess {
                logger.i { "MainViewModel.performSync: sync completed successfully" }
                loadTerminalId()
                _uiState.value = _uiState.value.copy(isLoading = false, syncCompleted = true)
            }.onError { uiError ->
                failed = true
                logger.e { "MainViewModel.performSync: complete sync failed - $uiError" }
                _uiState.value = _uiState.value.copy(isLoading = false, error = uiError)
            }
        return !failed
    }

    /**
     * Refreshes the application state.
     * Can be used for manual refresh triggers in the UI.
     */
    fun refresh() {
        // For mobile platforms, just perform sync since initialization is handled by desktop
        performSync()
    }
}

/**
 * Backoff between sync attempts: quick twice, then every 10 seconds, so a till recovers within
 * seconds of the server coming back without hammering it while it is down.
 */
private val DEFAULT_RETRY_DELAYS_MS = listOf(2_000L, 5_000L, 10_000L)

/**
 * UI state data class for MainViewModel.
 * Contains all state information needed by the main application UI.
 * Simplified for mobile platforms that don't handle initialization.
 */
data class MainUiState(
    val isLoading: Boolean = false,
    val error: UiError? = null,
    val syncCompleted: Boolean = false,
    val terminalId: String? = null,
)
