package org.example.project.features.auth.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.example.project.core.auth.PermissionManager
import org.example.project.core.auth.SignedInPerson
import org.example.project.core.data.remote.AuthApiClient
import org.example.project.core.device.DeviceInfoProvider
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.keys.TemporarySessionKeys
import org.example.project.features.auth.data.remote.TemporarySessionApiClient
import org.example.project.features.auth.presentation.state.TemporarySessionUiState
import kotlin.time.Clock

/**
 * ViewModel for managing temporary manager session lifecycle.
 *
 * Handles privilege escalation sessions for BASIC_EMPLOYEE users with:
 * - Countdown timer that updates every second
 * - Automatic extension when < 30 seconds remaining
 * - Server synchronization every 30 seconds
 * - Activity-based session extension
 * - Clean session creation and termination
 * - Ending the elevation locally when its person signs out or another person signs in
 *
 * Stage C migrated: errors surface as [UiError] in [TemporarySessionUiState.error].
 *
 * @property apiClient API client for temporary session endpoints
 * @property permissionManager Manager for role-based access control
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
class TemporarySessionManager(
    private val apiClient: TemporarySessionApiClient,
    private val permissionManager: PermissionManager,
    private val authApiClient: AuthApiClient,
) : ViewModel() {
    private val logger = Logger.Companion.withTag("TemporarySessionManager")

    private val _uiState = MutableStateFlow(TemporarySessionUiState())
    val uiState: StateFlow<TemporarySessionUiState> = _uiState.asStateFlow()

    private var countdownJob: Job? = null
    private var syncJob: Job? = null

    /**
     * Parent of everything an active step-up runs (countdown, server sync, extends), so ending it
     * cancels that work in one place — including an extend still waiting for the server, whose late
     * answer would otherwise revive a session that has already ended.
     */
    private var sessionWork: Job? = null

    /**
     * The signed-in person ([currentPrincipal]) the open dialog or the active step-up belongs to.
     * This manager is root-scoped and outlives a sign-out; see [observeSignOut].
     */
    private var owner: SignedInPerson? = null

    companion object {
        private const val COUNTDOWN_INTERVAL_MS = 1000L
        private const val SYNC_INTERVAL_MS = 30_000L
        private const val AUTO_EXTEND_THRESHOLD_SECONDS = 30
    }

    init {
        // Check for existing session on startup
        checkForExistingSession()
        // Start periodic server sync
        startServerSync()
        // End the elevation when its person signs out or another person signs in
        observeSignOut()
    }

    /**
     * Ends the step-up on this till when the person it belongs to is no longer the one signed in.
     *
     * The manager is obtained with `koinViewModel()` in `MainScreen`, which has no NavHost entry
     * above it, so it lives in the root ViewModelStore and survives a sign-out. Without this, K001's
     * step-up kept its countdown and its server sync running after K001 signed out, and once M001
     * signed in every navigation tried to extend K001's session (`AUTH_FORBIDDEN`, browser pass 1).
     *
     * Ended locally only: once the person has signed out there is no session left to call the
     * server with, and a step-up is bound to the session that requested it.
     */
    private fun observeSignOut() {
        viewModelScope.launch {
            permissionManager.signedInPerson.collect { principal ->
                if (principal == owner) return@collect
                val state = _uiState.value
                if (state.activeSession == null && !state.isDialogVisible) return@collect
                logger.i { "The signed-in person changed; ending the temporary session on this till" }
                endForSignOut()
            }
        }
    }

    /**
     * Who is signed in, keyed on the token's employee id ([SignedInPerson]). Not the stored employee
     * number: that can be absent, and two people without one compared equal, so a direct switch
     * between them went unnoticed (G review, M3).
     */
    private fun currentPrincipal(): SignedInPerson = permissionManager.signedInPerson.value

    /** Clears everything the previous person left here: the step-up, its jobs, the dialog. */
    private fun endForSignOut() {
        deactivateSession()
        owner = null
        _uiState.value = TemporarySessionUiState()
    }

    /** The scope a step-up's own work runs in, created with the step-up and cancelled when it ends. */
    private fun sessionScope(): CoroutineScope {
        val work = sessionWork ?: SupervisorJob(viewModelScope.coroutineContext[Job]).also { sessionWork = it }
        return CoroutineScope(viewModelScope.coroutineContext + work)
    }

    /**
     * Check for an existing active session on app startup.
     */
    private fun checkForExistingSession() {
        viewModelScope.launch {
            runCatchingApi { apiClient.getActiveSession() }
                .onSuccess { session ->
                    if (session != null) {
                        logger.i { "Found existing session: ${session.sessionId}" }
                        activateSession(session)
                    }
                }.onError { uiError ->
                    logger.w { "Failed to check for existing session: $uiError" }
                }
        }
    }

    /**
     * Start periodic server synchronization to validate session is still active.
     */
    private fun startServerSync() {
        syncJob?.cancel()
        if (!_uiState.value.hasActiveSession) return
        syncJob =
            sessionScope().launch {
                while (isActive && _uiState.value.hasActiveSession) {
                    delay(SYNC_INTERVAL_MS)

                    _uiState.value.activeSession ?: continue

                    runCatchingApi { apiClient.getActiveSession() }
                        .onSuccess { sessionData ->
                            if (sessionData == null) {
                                logger.w { "Session no longer active on server" }
                                endElevation(capReached = _uiState.value.isAtHardCap)
                            } else {
                                // Update remaining seconds from server
                                _uiState.update {
                                    it.copy(
                                        activeSession = sessionData,
                                        remainingSeconds = sessionData.remainingSeconds,
                                        lastSyncTimestamp = Clock.System.now().toEpochMilliseconds(),
                                    )
                                }
                            }
                        }.onError { uiError ->
                            logger.e { "Server sync failed: $uiError" }
                        }
                }
            }
    }

    /**
     * Show the temporary session authentication dialog.
     */
    fun showDialog() {
        owner = currentPrincipal()
        _uiState.update { it.copy(isDialogVisible = true, error = null) }
    }

    /**
     * Hide the temporary session authentication dialog.
     */
    fun hideDialog() {
        _uiState.update { it.copy(isDialogVisible = false, error = null) }
    }

    /**
     * Create a new temporary manager session.
     *
     * @param managerUsername Manager's username
     * @param managerPin Manager's 4-digit PIN
     */
    fun createSession(
        managerUsername: String,
        managerPin: String,
    ) {
        if (managerUsername.isBlank()) {
            _uiState.update { it.copy(error = usernameRequiredError()) }
            return
        }

        if (managerPin.length != 4) {
            _uiState.update { it.copy(error = invalidPinError()) }
            return
        }

        _uiState.update { it.copy(isLoading = true, error = null) }
        val requestedBy = currentPrincipal()

        viewModelScope.launch {
            // Get device ID from DeviceInfoProvider (consistent with terminal registration)
            val deviceId =
                try {
                    DeviceInfoProvider.getDeviceId()
                } catch (e: Exception) {
                    logger.e(e) { "Failed to get device ID" }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = deviceIdUnavailableError(),
                        )
                    }
                    return@launch
                }

            val request =
                AuthDto.TemporarySessionRequestDto(
                    managerUsername = managerUsername.trim(),
                    managerPin = managerPin,
                    deviceId = deviceId,
                )

            runCatchingApi { apiClient.createSession(request) }
                .onSuccess { session ->
                    if (currentPrincipal() != requestedBy) {
                        // The person who asked signed out while the server answered.
                        logger.w { "Session ${session.sessionId} arrived after its requester signed out; not activating it" }
                        endForSignOut()
                        return@onSuccess
                    }
                    logger.i { "Session created successfully: ${session.sessionId}" }
                    activateSession(session)
                    _uiState.update {
                        it.copy(
                            isDialogVisible = false,
                            isLoading = false,
                            error = null,
                        )
                    }
                }.onError { uiError ->
                    logger.w { "Failed to create session: $uiError" }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = uiError,
                        )
                    }
                }
        }
    }

    /**
     * Clear error state.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Clear success message.
     */
    fun clearSuccessMessage() {
        _uiState.update { it.copy(successMessage = null) }
    }

    /**
     * Extend the current session. The server moves the expiry forward, but never past the session's
     * hard cap; once the cap is reached it refuses with [PosErrorCode.AUTH_STEP_UP_CAP_REACHED], and
     * the elevation ends here too — the manager must enter the PIN again.
     */
    fun extendSession() {
        val sessionId = _uiState.value.activeSession?.sessionId ?: return
        if (currentPrincipal() != owner) {
            // Another person is signed in by now; never extend the previous one's step-up.
            endForSignOut()
            return
        }

        sessionScope().launch {
            runCatchingApi { apiClient.extendSession(sessionId) }
                .onSuccess { session ->
                    logger.i { "Session extended: $sessionId until ${session.expiresAt}" }
                    _uiState.update { it.copy(activeSession = session, remainingSeconds = session.remainingSeconds) }
                }.onError { uiError ->
                    if (uiError.code == PosErrorCode.AUTH_STEP_UP_CAP_REACHED) {
                        logger.i { "Session $sessionId reached its hard cap; ending the elevation" }
                        endElevation(capReached = true)
                    } else {
                        logger.e { "Failed to extend session: $uiError" }
                    }
                }
        }
    }

    /**
     * The notice that the manager approval expired has been shown.
     */
    fun consumeApprovalExpiredNotice() {
        _uiState.update { it.copy(approvalExpiredNotice = false, approvalExpiredAtCap = false) }
    }

    /**
     * End the elevation because it ran out or the server ended it, raising the one-shot
     * [TemporarySessionUiState.approvalExpiredNotice]: the lent authority silently vanishing mid-task
     * left operators with a Storno icon gone and no explanation. [capReached] picks the wording.
     */
    private fun endElevation(capReached: Boolean) {
        deactivateSession()
        _uiState.update { it.copy(approvalExpiredNotice = true, approvalExpiredAtCap = capReached) }
    }

    /**
     * Manually end the current session.
     */
    fun endSession() {
        val sessionId = _uiState.value.activeSession?.sessionId ?: return

        viewModelScope.launch {
            runCatchingApi { apiClient.endSession(sessionId) }
                .onSuccess {
                    logger.i { "Session ended successfully: $sessionId" }
                    deactivateSession()
                }.onError { uiError ->
                    logger.e { "Failed to end session: $uiError" }
                    // Deactivate locally anyway
                    deactivateSession()
                }
        }
    }

    /**
     * Track navigation change and extend session if there's an active session.
     * This keeps the session alive as long as the user is actively navigating.
     */
    fun trackNavigationChange() {
        if (_uiState.value.hasActiveSession) {
            logger.d { "Navigation detected, extending session" }
            extendSession()
        }
    }

    /**
     * Track privileged operation and extend session if needed.
     */
    fun trackPrivilegedOperation() {
        if (_uiState.value.isExpiringSoon) {
            logger.d { "Privileged operation detected, extending session" }
            extendSession()
        }
    }

    /**
     * Activate a session and start countdown timer.
     */
    private fun activateSession(session: AuthDto.TemporarySessionResponseDto) {
        owner = currentPrincipal()
        _uiState.update {
            it.copy(
                activeSession = session,
                remainingSeconds = session.remainingSeconds,
            )
        }

        // The temporary ROLE is still set, for the legacy role-backed policy an older server
        // leaves the client falling back to. It is no longer what the server does: a step-up unions
        // capabilities onto the cashier's own set and does not change who they are. Where the server
        // reports capabilities, `adoptElevatedCapabilities` below is the authority and the role is
        // ignored.
        val managerRole =
            try {
                EmployeeRole.valueOf(session.managerRole)
            } catch (e: IllegalArgumentException) {
                logger.w(e) { "Invalid manager role: ${session.managerRole}, defaulting to MANAGER" }
                EmployeeRole.MANAGER
            }
        permissionManager.setTemporaryRole(managerRole)
        adoptElevatedCapabilities()

        // Start countdown timer
        startCountdownTimer()

        // Restart server sync
        startServerSync()
    }

    /**
     * Deactivate session and cleanup.
     */

    /**
     * Adopt the capability set the server will actually enforce for the life of the session.
     *
     * Asks the SERVER rather than deriving anything from `session.managerRole`: `/api/auth/me`
     * answers from what the route gate resolved for that very request, so it already includes what
     * the step-up lent. Deriving from a role would be wrong the moment an approver sits in a
     * purpose-built group — the exact class of bug this track exists to remove.
     *
     * This still REPLACES the client's cached set, and that is now correct rather than a compromise:
     * since the step-up stage the server unions server-side, so what `/api/auth/me` returns IS the
     * union. The old note here — that elevating could NARROW what the till may do, because the
     * approver's own restrictions replaced the cashier's — no longer applies. That was the defect
     * the step-up stage removed.
     *
     * A failure leaves the temporary set unset, so the policy falls back to the role — degrading to
     * the previous behaviour rather than to a blank UI.
     */
    private fun adoptElevatedCapabilities() {
        sessionScope().launch {
            runCatching { authApiClient.getCurrentUser() }
                .onSuccess { permissionManager.setTemporaryCapabilities(it.capabilities) }
                .onFailure { logger.w(it) { "Could not read elevated capabilities; falling back to the role" } }
        }
    }

    private fun deactivateSession() {
        // Clear temporary role in PermissionManager
        permissionManager.clearTemporaryRole()
        permissionManager.clearTemporaryCapabilities()

        // Stop countdown timer
        countdownJob?.cancel()
        countdownJob = null

        // Stop server sync
        syncJob?.cancel()
        syncJob = null

        // Stop everything else the step-up started (an extend in flight included)
        sessionWork?.cancel()
        sessionWork = null

        // Clear state
        _uiState.update { it.copy(activeSession = null, remainingSeconds = 0) }
    }

    /**
     * Start countdown timer that decrements every second.
     */
    private fun startCountdownTimer() {
        countdownJob?.cancel()
        countdownJob =
            sessionScope().launch {
                while (isActive) {
                    delay(COUNTDOWN_INTERVAL_MS)

                    val currentSeconds = _uiState.value.remainingSeconds

                    if (currentSeconds <= 0) {
                        logger.w { "Session expired" }
                        endElevation(capReached = _uiState.value.isAtHardCap)
                        break
                    }

                    // Update remaining time
                    _uiState.update { it.copy(remainingSeconds = currentSeconds - 1) }

                    // Auto-extend if at threshold and there's an active session
//                if (currentSeconds - 1 == AUTO_EXTEND_THRESHOLD_SECONDS) {
//                    logger.d { "Session about to expire, auto-extending" }
//                    extendSession()
//                }
                }
            }
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
        syncJob?.cancel()
    }

    private fun usernameRequiredError(): UiError =
        UiError.Unknown(
            titleKey = TemporarySessionKeys.dialogTitle,
            messageKey = TemporarySessionKeys.errorUsernameRequired,
        )

    private fun invalidPinError(): UiError =
        UiError.Unknown(
            titleKey = TemporarySessionKeys.dialogTitle,
            messageKey = TemporarySessionKeys.errorInvalidPin,
        )

    private fun deviceIdUnavailableError(): UiError =
        UiError.Unknown(
            titleKey = TemporarySessionKeys.dialogTitle,
            messageKey = TemporarySessionKeys.errorDeviceIdUnavailable,
        )
}
