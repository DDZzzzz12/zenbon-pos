package org.example.project.features.auth.presentation.viewmodels

import androidx.lifecycle.ViewModel
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.project.core.auth.PermissionManager
import org.example.project.core.auth.SessionStatus
import org.example.project.core.auth.TokenStore
import org.example.project.core.data.database.LocalDatabaseManager
import org.example.project.core.network.NotificationConnection
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.notification.PlatformNotificationService
import org.example.project.core.notification.markLoggedInAndStartNotifications
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.keys.LoginKeys
import org.example.project.core.utils.platformIoDispatcher
import org.example.project.features.auth.domain.usecase.LoginUseCase
import org.example.project.features.auth.presentation.state.RememberedLogin
import org.example.project.features.setup.data.remote.SetupApiClient
import org.example.project.ui.auth.SessionState
import kotlin.time.Duration.Companion.milliseconds

/**
 * ViewModel for managing login screen state and authentication logic.
 *
 * Stage C migrated: errors surface as [UiError] in [LoginUiState.error].
 * Screens resolve the localized text via `str(error.messageKey, *args)`.
 */
class LoginViewModel(
    private val loginUseCase: LoginUseCase,
    private val localDB: LocalDatabaseManager,
    private val permissionManager: PermissionManager,
    private val tokenStore: TokenStore,
    private val setupApiClient: SetupApiClient,
    private val platformNotificationService: PlatformNotificationService,
    private val sessionState: SessionState,
    private val notificationConnection: NotificationConnection,
    private val ioDispatcher: CoroutineDispatcher = platformIoDispatcher(),
) : ViewModel() {
    private val logger = Logger.withTag("LoginViewModel")

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main)
    private var healthCheckJob: Job? = null

    init {
        // The startup wipe is owned by SessionState (constructed with StartupPolicy.WIPE), which
        // runs earlier and deterministically when Koin first resolves it at the app root.
        startHealthCheck()
    }

    /**
     * The credentials saved with "remember me", or null when remember-me is off or they cannot be read right now. Reads
     * on [ioDispatcher]: the password is sealed, and opening it can block on the Keystore or keychain.
     *
     * Returned, never held in [uiState]: this view model lives as long as the app, so a password parked in its state
     * outlived a login screen that left before taking it, and could later pre-fill a password the user had asked to
     * forget. The caller's coroutine owns the value, so leaving the screen cancels the load.
     */
    suspend fun loadRememberedLogin(): RememberedLogin? =
        withContext(ioDispatcher) {
            try {
                if (localDB.getLoginRememberMe() != true) return@withContext null
                RememberedLogin(
                    identifier = localDB.getLoginUserData().orEmpty(),
                    secret = localDB.getLoginPassword().orEmpty(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(e) { "Could not load the remembered login — showing an empty form" }
                null
            }
        }

    fun login(
        identifier: String,
        secret: String,
        rememberMe: Boolean,
        onSuccess: (String) -> Unit,
    ) {
        if (identifier.isBlank() || secret.isBlank()) {
            _uiState.update {
                it.copy(error = fillAllFieldsError())
            }
            return
        }

        logger.i { "LoginViewModel.login: attempting login for identifier=$identifier" }
        _uiState.update {
            it.copy(
                isLoading = true,
                error = null,
            )
        }
        scope.launch {
            // The use case stores the session, remember-me credentials included, and returns a failure
            // — never throws — when that storage fails, so onError covers it as UiError.Unknown.
            loginUseCase(identifier, secret, rememberMe)
                .onSuccess { outcome ->
                    logger.i { "LoginViewModel.login: login successful for identifier=$identifier" }

                    if (outcome.needsPairing) {
                        // Tokens are stored & valid, but the server requires this device to
                        // pair before it can act as a fiscal terminal. Route to pairing
                        // instead of entering the app.
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                requiresPairing = true,
                            )
                        }
                        stopHealthCheck()
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                            )
                        }
                        // Resolve the access policy from the tokens LoginUseCase just stored BEFORE
                        // the stream starts. NotificationReconciler reads it once after the SSE
                        // handshake, and SettingProvider's own loadRoleFromToken only runs once
                        // markLoggedIn has recomposed — a handshake that wins that race skipped a
                        // manager's pending-approvals replay until the next reconnect.
                        // The access token is sealed, so the read runs on the IO dispatcher.
                        withContext(ioDispatcher) { permissionManager.readRoleSnapshot() }?.let(permissionManager::applyRoleSnapshot)
                        // The session before the service (NOTE-8): see markLoggedInAndStartNotifications.
                        sessionState.markLoggedInAndStartNotifications(platformNotificationService)
                        if (outcome.language != null) {
                            onSuccess(outcome.language)
                        }
                        stopHealthCheck()
                    }
                }.onError { uiError ->
                    logger.e { "LoginViewModel.login: login failed - $uiError" }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = uiError,
                        )
                    }
                }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Signs the user out. Returns immediately — the server call runs on [scope].
     *
     * [onComplete] fires once the local session is fully torn down, whether or not the server
     * call succeeded. Callers that end the process (the exit-app dialog) MUST wait for it:
     * `exitProcess` on the line after `logout()` kills the coroutine before
     * `POST /api/auth/logout` is sent, leaving the refresh token live on the server.
     */
    fun logout(onComplete: () -> Unit = {}) {
        logger.i { "LoginViewModel.logout: logging out" }
        // Clear permissions on logout
        permissionManager.clearRole()

        _uiState.value = LoginUiState()

        scope.launch {
            // Close the notification stream while the session is still whole — before the server is
            // told and before the tokens go — so a deliberate sign-out is silent. stopService() alone
            // only does this on Android: the browser and desktop clients have no service, so their
            // stream outlived the logout, the server ended it, and its reconnect found no token and
            // raised "Authentifizierungsfehler – keine aktive Sitzung" (browser pass 1, bug 9).
            // disconnect() also invalidates a reconnect already scheduled, so nothing of this
            // stream can report a failure afterwards. A real session loss still does: that path
            // does not come through here.
            closeNotificationStream()
            platformNotificationService.stopService()
            delay(500)
            // Call logout API while tokens are still valid. logout() swallows
            // API failures and always clears the local session.
            try {
                loginUseCase.logout()
                logger.i { "LoginViewModel.logout: logout successful" }
                // Clear JWT tokens after successful logout
                clearLocalSession()
            } catch (e: Exception) {
                logger.e(e) { "LoginViewModel.logout: logout failed" }
                // Still clear tokens on failure to ensure clean state
                clearLocalSession()
                _uiState.update {
                    it.copy(
                        error = logoutFailedError(),
                    )
                }
            }
            onComplete()
        }
    }

    /**
     * Clears the tokens, ends the session, and then clears the role again. [logout] cleared the role when it began, but
     * the tokens stay until the server call returns, and a role read in that gap — `SettingProvider` reads a snapshot on
     * the IO dispatcher and publishes it afterwards — can still find the access token and publish the role back.
     */
    private fun clearLocalSession() {
        tokenStore.clear()
        sessionState.logout()
        permissionManager.clearRole()
    }

    /**
     * The first step of [logout], so it must never take the rest of the teardown with it: a failure
     * here would leave the session alive on the server and the tokens on the till. Logged and
     * passed over; cancellation still propagates.
     */
    private suspend fun closeNotificationStream() {
        try {
            notificationConnection.disconnect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(e) { "LoginViewModel.logout: closing the notification stream failed; signing out anyway" }
        }
    }

    fun refreshUrl() {
        setupCheckDone = false
        // Restart health check with new URL
        stopHealthCheck()
        startHealthCheck()
        _uiState.update { it.copy(isLoading = false, requiresSetup = false) }
    }

    /**
     * Start periodic health check to monitor server connectivity.
     * Checks the /health endpoint every 5 seconds.
     */
    private fun startHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob =
            scope.launch {
                while (isActive) {
                    checkServerHealth()
                    delay(5000.milliseconds) // Check every 5 seconds for remote server
                }
            }
    }

    /**
     * Stop the periodic health check.
     *
     * Public so tests can cancel the `init`-launched health-check loop before calling
     * `advanceUntilIdle()`: the unbounded `while(isActive) { checkServerHealth();
     * delay(5000) }` loop otherwise drives `advanceUntilIdle` into a virtual-time
     * spin for tests that never reach a successful-login `stopHealthCheck()` call.
     * No production caller behavior changes.
     */
    fun stopHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = null
    }

    /**
     * Polls `/health` only while nobody is signed in — it feeds the login/pairing screen's status dot.
     *
     * A person's [login] already stops it, but the order station never logs in here: its session comes
     * from `LaunchCoordinator`'s exchange, so the station kept polling every 5 s for its whole shift.
     * Leaving the session (logout, cold reset) restarts it, so the login screen's dot is live again.
     */
    fun onSessionStatusChanged(status: SessionStatus) {
        when (status) {
            SessionStatus.AUTHENTICATED -> stopHealthCheck()
            SessionStatus.UNAUTHENTICATED -> if (healthCheckJob?.isActive != true) startHealthCheck()
            SessionStatus.CHECKING -> Unit
        }
    }

    /** Tracks whether setup status has already been checked for this connection */
    private var setupCheckDone = false

    /**
     * Perform a single health check on the server.
     * When first connected, also checks if the system requires initial setup.
     */
    private suspend fun checkServerHealth() {
        // Only announce CHECKING when there is nothing better to show. Setting it unconditionally on
        // entry meant every 5 s poll flickered a perfectly reachable server's badge through
        // Online -> Checking -> Online. While disconnected the badge still shows the retry.
        if (_uiState.value.serverStatus != ServerStatus.CONNECTED) {
            _uiState.update { it.copy(serverStatus = ServerStatus.CHECKING) }
        }

        // runCatchingApi handles PosApiException / NetworkApiException -> DISCONNECTED.
        // Wrap in try/catch to preserve the legacy ERROR state for unexpected
        // exceptions (e.g. serialization bugs) that runCatchingApi doesn't catch.
        try {
            runCatchingApi { setupApiClient.healthCheck() }
                .onSuccess {
                    logger.d { "LoginViewModel.checkServerHealth: server connected" }
                    _uiState.update { it.copy(serverStatus = ServerStatus.CONNECTED) }

                    // Check setup status once per connection
                    if (!setupCheckDone) {
                        checkSetupStatus()
                    }
                }.onError { uiError ->
                    logger.d { "LoginViewModel.checkServerHealth: server disconnected ($uiError)" }
                    setupCheckDone = false
                    _uiState.update {
                        it.copy(serverStatus = ServerStatus.DISCONNECTED, requiresSetup = false)
                    }
                }
        } catch (e: Exception) {
            logger.e(e) { "LoginViewModel.checkServerHealth: health check error" }
            setupCheckDone = false
            _uiState.update { it.copy(serverStatus = ServerStatus.ERROR, requiresSetup = false) }
        }
    }

    /**
     * Checks if the system requires initial setup.
     * Called once when server connection is first established.
     */
    private suspend fun checkSetupStatus() {
        try {
            runCatchingApi { setupApiClient.checkSetupStatus() }
                .onSuccess { status ->
                    setupCheckDone = true
                    _uiState.update { it.copy(requiresSetup = status.requiresSetup) }
                }.onError { _ ->
                    // If setup status check fails, don't block login flow
                    setupCheckDone = true
                    _uiState.update { it.copy(requiresSetup = false) }
                }
        } catch (_: Exception) {
            // Defensive: any unexpected non-API exception still must not block login
            setupCheckDone = true
            _uiState.update { it.copy(requiresSetup = false) }
        }
    }

    /**
     * Manually trigger a health check.
     * Useful for user-initiated refresh.
     */
    fun checkHealth() {
        scope.launch {
            checkServerHealth()
        }
    }

    /**
     * Clean up resources when ViewModel is cleared.
     */
    override fun onCleared() {
        super.onCleared()
        stopHealthCheck()
    }

    private fun fillAllFieldsError(): UiError =
        UiError.Unknown(
            titleKey = LoginKeys.title,
            messageKey = LoginKeys.errorFillAllFields,
        )

    private fun logoutFailedError(): UiError =
        UiError.Unknown(
            titleKey = LoginKeys.title,
            messageKey = LoginKeys.errorLogoutFailed,
        )
}

/**
 * Server connectivity status.
 */
enum class ServerStatus {
    CHECKING,
    CONNECTED,
    DISCONNECTED,
    ERROR,
}

/**
 * UI state for the login screen.
 *
 * Navigation state lives in [SessionState] (`status`), not here: `isAuthenticated`/`isLoggedOut`
 * were redundant with it and raced the session flip. This state holds only login-form state
 * and login-flow signals (setup required, pairing required, server status, error).
 */
data class LoginUiState(
    val isLoading: Boolean = false,
    val error: UiError? = null,
    val serverStatus: ServerStatus = ServerStatus.CHECKING,
    /** Whether the system requires initial setup (no admin user or shop configured) */
    val requiresSetup: Boolean = false,
    /** True after a successful login that answered TERMINAL_NEEDS_PAIRING — route to pairing. */
    val requiresPairing: Boolean = false,
)
