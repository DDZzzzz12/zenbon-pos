package org.example.project.features.auth.domain.usecase

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.example.project.auth.data.LoginRequest
import org.example.project.auth.data.LoginResponse
import org.example.project.core.auth.TokenStore
import org.example.project.core.data.database.LocalDatabaseManager
import org.example.project.core.data.domain.service.TerminalRegistrationService
import org.example.project.core.data.provider.RTableDataProvider
import org.example.project.core.data.provider.RoomElementDataProvider
import org.example.project.core.data.remote.AuthApiClient
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.network.error.PosApiException
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.utils.platformIoDispatcher

/**
 * Use case for handling user login and logout.
 *
 * [invoke] returns a [Result]; callers map a failure to a
 * [org.example.project.core.presentation.error.UiError] with `onError { ... }`.
 *
 * [logout] swallows API failures (logout always succeeds locally regardless
 * of server outcome) and returns [Unit].
 *
 * Local session storage runs on [ioDispatcher]: sealing or clearing a token can block on the Keystore or keychain,
 * so it never runs on the caller's UI thread.
 */
class LoginUseCase(
    private val apiClient: AuthApiClient,
    private val tokenStore: TokenStore,
    private val localDatabaseManager: LocalDatabaseManager,
    private val terminalRegistrationService: TerminalRegistrationService,
    private val rTableDataProvider: RTableDataProvider,
    private val roomElementDataProvider: RoomElementDataProvider,
    private val ioDispatcher: CoroutineDispatcher = platformIoDispatcher(),
) {
    private val logger = Logger.withTag("LoginUseCase")

    /**
     * Logs the user out. Best-effort: server-side logout failures are
     * swallowed so that local session state is always cleared.
     */
    suspend fun logout() {
        try {
            apiClient.logout()
        } catch (_: PosApiException) {
            // Logout still succeeds locally regardless of server outcome
        } catch (_: NetworkApiException) {
            // Logout still succeeds locally regardless of network state
        }

        // Clear local session regardless of API result
        withContext(ioDispatcher) { clearLocalSession() }

        // Forget per-session table/room-element seeding so the next login re-fetches
        // fresh data instead of showing the prior session's cached rows. The next
        // ensureSeeded's refresh overwrites the SQLDelight cache, so no explicit cache
        // clear is needed here (clearing without resetSeed would blank the dashboard).
        rTableDataProvider.resetSeed()
        roomElementDataProvider.resetSeed()
    }

    /**
     * Authenticates the user and stores the session locally. Succeeds with a [LoginOutcome] (the
     * employee's preferred language code, plus whether background terminal registration answered
     * TERMINAL_NEEDS_PAIRING). Fails with:
     * - [PosApiException] (e.g. [PosApiException.code] == [PosErrorCode.AUTH_INVALID_CREDENTIALS]) or
     *   [NetworkApiException] from the login call;
     * - [IllegalArgumentException] when the response carries no refresh token — checked before anything
     *   is stored, so nothing is written and nothing an earlier login stored is touched;
     * - the storage exception when the session could not be stored locally — sealing a token or the
     *   remember-me password failed. No part of the session is left behind.
     *
     * Caller is responsible for input validation (empty identifier/secret).
     */
    suspend operator fun invoke(
        identifier: String,
        secret: String,
        rememberMe: Boolean = false,
    ): Result<LoginOutcome> {
        val request =
            LoginRequest(
                identifier = identifier.trim(),
                secret = secret,
            )

        val loginData = runCatchingApi { apiClient.login(request) }.getOrElse { return Result.failure(it) }
        // Not a storage failure, and no reason to wipe an earlier login's remember-me credentials.
        val refreshToken =
            loginData.token.refreshToken ?: run {
                logger.w { "Login response carries no refresh token — storing nothing" }
                return Result.failure(IllegalArgumentException("Login response carries no refresh token"))
            }
        storeSession(loginData, refreshToken, identifier, secret, rememberMe).onFailure { return Result.failure(it) }

        // Register terminal in background; surface ONLY a needs-pairing 409.
        val needsPairing = registerTerminalInBackground()

        return Result.success(LoginOutcome(language = loginData.employee.language, needsPairing = needsPairing))
    }

    /**
     * Stores the token pair — [refreshToken] is the response's own, already checked non-null — the employee and the
     * remember-me credentials (cleared when [rememberMe] is false), on [ioDispatcher]. A secret that cannot be sealed
     * (Keystore or keychain unavailable) fails the whole step: the store writes nothing for that key and whatever was
     * already stored is cleared, so the app never starts half-authenticated.
     */
    private suspend fun storeSession(
        loginData: LoginResponse,
        refreshToken: String,
        identifier: String,
        secret: String,
        rememberMe: Boolean,
    ): Result<Unit> =
        withContext(ioDispatcher) {
            try {
                tokenStore.storeTokens(
                    accessToken = loginData.token.accessToken,
                    refreshToken = refreshToken,
                    expiresIn = loginData.token.expiresIn,
                )
                localDatabaseManager.setUserInfo(loginData.employee)
                // And into the token store, which the refresh path also writes — so the two never disagree
                // about which set is current. Empty means the server did not report, which must stay
                // distinguishable from "holds nothing"; see TokenStore.capabilities.
                tokenStore.capabilities = loginData.employee.capabilities.ifEmpty { null }
                localDatabaseManager.setLoginUserData(identifier, secret, rememberMe)
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(e) { "Could not store the session locally — discarding it" }
                clearLocalSession()
                Result.failure(e)
            }
        }

    /** Removes the tokens, the employee and the remember-me credentials. */
    private fun clearLocalSession() {
        tokenStore.clear()
        localDatabaseManager.clearLoginUserData()
        localDatabaseManager.clearUserInfo()
    }

    /**
     * Best-effort terminal registration. Returns true only when the server answered
     * TERMINAL_NEEDS_PAIRING (409); all other failures (network/unknown) are swallowed
     * so a transient registration error never blocks an otherwise-successful login.
     */
    private suspend fun registerTerminalInBackground(): Boolean {
        var needsPairing = false
        runCatchingApi { terminalRegistrationService.ensureRegistered() }
            .onSuccess { terminal ->
                logger.i { "Terminal registered: ${terminal.terminalId}" }
            }.onError { uiError ->
                if (uiError is UiError.FromServer && uiError.code == PosErrorCode.TERMINAL_NEEDS_PAIRING) {
                    needsPairing = true
                    logger.i { "Terminal needs pairing (409)" }
                } else {
                    logger.w { "Terminal registration failed: $uiError" }
                }
            }
        return needsPairing
    }
}
