package org.example.project.features.settings.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.project.auth.data.SessionPolicyDto
import org.example.project.core.data.remote.SessionPolicyApiClient
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError

/**
 * ViewModel for the Security / Sessions settings section.
 *
 * Manages the admin-only daily re-login policy (enabled flag + reset time)
 * and the revoke-all-sessions (panic) action. Validates the reset time as
 * an HH:mm string before allowing a save.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
class SecuritySessionsViewModel(
    private val sessionPolicyApiClient: SessionPolicyApiClient,
) : ViewModel() {
    private val logger = Logger.withTag("SecuritySessionsViewModel")

    private val _uiState = MutableStateFlow(SecuritySessionsUiState())
    val uiState: StateFlow<SecuritySessionsUiState> = _uiState.asStateFlow()

    companion object {
        private val TIME_REGEX = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
        private const val DEFAULT_RESET_TIME = "04:00"
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatchingApi { sessionPolicyApiClient.getPolicy() }
                .onSuccess { policy ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            dailyResetEnabled = policy.dailyResetEnabled,
                            dailyResetTime = policy.dailyResetTime.ifBlank { DEFAULT_RESET_TIME },
                            savedDailyResetEnabled = policy.dailyResetEnabled,
                            savedDailyResetTime = policy.dailyResetTime.ifBlank { DEFAULT_RESET_TIME },
                        )
                    }
                }.onError { uiError ->
                    logger.e { "Failed to load session policy: $uiError" }
                    _uiState.update { it.copy(isLoading = false, error = uiError) }
                }
        }
    }

    fun setDailyResetEnabled(enabled: Boolean) {
        _uiState.update { it.copy(dailyResetEnabled = enabled, saved = false) }
    }

    fun setDailyResetTime(time: String) {
        _uiState.update { it.copy(dailyResetTime = time, saved = false) }
    }

    fun isTimeValid(time: String): Boolean = TIME_REGEX.matches(time)

    fun save() {
        if (!isTimeValid(_uiState.value.dailyResetTime)) return
        viewModelScope.launch {
            val current = _uiState.value
            _uiState.update { it.copy(isSaving = true, error = null, saved = false) }
            runCatchingApi {
                sessionPolicyApiClient.updatePolicy(
                    SessionPolicyDto.UpdatePolicyRequest(
                        dailyResetEnabled = current.dailyResetEnabled,
                        dailyResetTime = current.dailyResetTime,
                    ),
                )
            }.onSuccess { policy ->
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        saved = true,
                        dailyResetEnabled = policy.dailyResetEnabled,
                        dailyResetTime = policy.dailyResetTime,
                        savedDailyResetEnabled = policy.dailyResetEnabled,
                        savedDailyResetTime = policy.dailyResetTime,
                    )
                }
            }.onError { uiError ->
                logger.e { "Failed to save session policy: $uiError" }
                _uiState.update { it.copy(isSaving = false, error = uiError) }
            }
        }
    }

    fun revokeAll() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRevoking = true, error = null, revoked = false) }
            runCatchingApi { sessionPolicyApiClient.revokeAll() }
                .onSuccess {
                    _uiState.update { it.copy(isRevoking = false, revoked = true) }
                }.onError { uiError ->
                    logger.e { "Failed to revoke all sessions: $uiError" }
                    _uiState.update { it.copy(isRevoking = false, error = uiError) }
                }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearSaved() {
        _uiState.update { it.copy(saved = false) }
    }

    fun clearRevoked() {
        _uiState.update { it.copy(revoked = false) }
    }
}

data class SecuritySessionsUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isRevoking: Boolean = false,
    val dailyResetEnabled: Boolean = false,
    val dailyResetTime: String = "04:00",
    val saved: Boolean = false,
    val revoked: Boolean = false,
    val error: UiError? = null,
    /** What the server last returned for [dailyResetEnabled]; see [isDirty]. */
    val savedDailyResetEnabled: Boolean = false,
    /** What the server last returned for [dailyResetTime]. */
    val savedDailyResetTime: String = "04:00",
) {
    /** The policy draft differs from what was last loaded or saved. Edit-then-undo is clean. */
    val isDirty: Boolean
        get() = dailyResetEnabled != savedDailyResetEnabled || dailyResetTime != savedDailyResetTime
}
