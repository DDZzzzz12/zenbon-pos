package org.example.project.admin.features.auth.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.auth.authProvider
import io.ktor.client.plugins.auth.providers.BearerAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.auth.data.LoginRequest
import org.example.project.core.auth.TokenStore
import org.example.project.core.data.remote.AuthApiClient
import org.example.project.core.error.PosErrorCode
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.error.toUiError
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.ui.auth.SessionState

/**
 * Drives the admin login screen: form state, submission, and on success
 * persisting tokens and flipping the app-level session to logged-in.
 *
 * Login failures are surfaced as [UiError] and rendered by the shared [PosErrorWatcher]
 * in the global overlay host above the session boundary.
 */
class AdminLoginViewModel(
    private val authApiClient: AuthApiClient,
    private val tokenStore: TokenStore,
    private val sessionState: SessionState,
    private val httpClient: HttpClient,
    private val sessionEndedNotice: SessionEndedNotice,
) : ViewModel() {
    private val log = Logger.withTag("login")

    var state by mutableStateOf(AdminLoginState())
        private set

    fun onIdentifierChange(value: String) {
        state = state.copy(identifier = value)
    }

    fun onSecretChange(value: String) {
        state = state.copy(secret = value)
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    fun login() {
        if (!state.canSubmit) return
        // A new attempt supersedes the reason the previous session ended.
        sessionEndedNotice.clear()
        state = state.copy(isSubmitting = true, error = null)
        viewModelScope.launch {
            try {
                val response =
                    authApiClient.login(
                        LoginRequest(identifier = state.identifier.trim(), secret = state.secret),
                    )
                tokenStore.storeTokens(
                    accessToken = response.token.accessToken,
                    // adminApp's refresh token lives only in the HttpOnly cookie; never keep one
                    // that arrives in a body.
                    refreshToken = null,
                    expiresIn = response.token.expiresIn,
                )
                tokenStore.role = response.employee.role
                // What the server will actually enforce. Empty means it did not report — an older
                // server — which must stay distinguishable from "holds nothing"; see
                // TokenStore.capabilities and resolveAccessPolicy.
                tokenStore.capabilities = response.employee.capabilities.ifEmpty { null }
                // Display only: the sidebar footer names the group, not the role (R6-4).
                tokenStore.permissionGroupCode = response.employee.permissionGroupCode
                tokenStore.permissionGroupName = response.employee.permissionGroupName
                // Drop the Auth plugin's cached (pre-login) tokens so the new ones are used.
                httpClient.authProvider<BearerAuthProvider>()?.clearToken()
                // Reset the form/loading state: this VM is retained, so on a later logout
                // the login screen must not show a stale "submitting" spinner.
                state = AdminLoginState()
                log.d { "login success (role=${response.employee.role})" }
                sessionState.markLoggedIn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.w(e) { "login failed" }
                state =
                    state.copy(
                        isSubmitting = false,
                        error = mapLoginError(e),
                    )
            }
        }
    }

    /**
     * The server answers wrong credentials with 401/400 and other failures as genuine server errors.
     * Map them to localized [UiError.Network]/[UiError.Unknown] or a generic [UiError.FromServer].
     */
    private fun mapLoginError(e: Throwable): UiError =
        when (val ui = e.toUiError()) {
            is UiError.Network -> ui
            is UiError.Unknown -> ui
            is UiError.FromServer ->
                UiError.Unknown(
                    titleKey = CommonKeys.unknownErrorTitle,
                    messageKey = loginErrorMessageKey(ui.code),
                )
        }
}

/**
 * The login screen's message for a refused sign-in. Right credentials with no permission group is a
 * different problem from wrong credentials and needs a different sentence: the operator cannot fix it
 * by retyping.
 */
internal fun loginErrorMessageKey(code: PosErrorCode): StringKey =
    when (code) {
        PosErrorCode.NO_PERMISSION_GROUP -> AdminStringKeys.LoginErrorNoPermissionGroup
        else -> AdminStringKeys.LoginErrorInvalidCredentials
    }
