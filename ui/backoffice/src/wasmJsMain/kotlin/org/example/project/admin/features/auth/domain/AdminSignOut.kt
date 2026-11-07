package org.example.project.admin.features.auth.domain

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.example.project.core.data.remote.AuthApiClient
import org.example.project.ui.auth.SessionState
import kotlin.time.Duration.Companion.seconds

/**
 * Ends the admin's session on the server first, then locally.
 *
 * The refresh token is an HttpOnly cookie only `POST /api/auth/logout` can expire; flipping the local
 * session alone would let a reload resume it from the cookie. Order matters: the server call needs the
 * bearer still in the token store, so the local sign-out runs after it — and always runs (finally),
 * even when the server is unreachable, slow, or the caller is cancelled.
 *
 * Only for a sign-out the user asked for. A failed refresh (`onUnauthorized`) calls
 * [SessionState.logout] directly: the server has already rejected that session.
 */
class AdminSignOut(
    private val authApiClient: AuthApiClient,
    private val session: SessionState,
) {
    private val log = Logger.withTag("AdminSignOut")

    suspend operator fun invoke() {
        try {
            val completed = withTimeoutOrNull(SERVER_LOGOUT_TIMEOUT) { authApiClient.logout() }
            if (completed == null) {
                log.w { "Server logout timed out; signing out locally" }
            } else {
                log.i { "Server session ended" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Throwable, not Exception: on wasm a JS-side failure surfaces as JsException, which
            // `catch (e: Exception)` does not catch.
            log.w(e) { "Server logout failed; signing out locally" }
        } finally {
            session.logout()
        }
    }

    private companion object {
        /**
         * Bounds only the logout request itself. If that request 401s and triggers a cookie
         * refresh, the shared Ktor client runs the refresh non-cancellably — so a rotated
         * refresh cookie is never dropped mid-write — and this timeout cannot interrupt it.
         * In that case sign-out waits for the refresh to finish before the logout call
         * (successful or not) is subject to this bound.
         */
        val SERVER_LOGOUT_TIMEOUT = 5.seconds
    }
}
