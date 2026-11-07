package org.example.project.ui.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.example.project.core.auth.SessionStatus
import org.example.project.core.auth.TokenStore

/**
 * App-level authentication state shared by composeApp and adminApp. Reading [status] in
 * composition drives the checking / app / login switch reactively.
 *
 * There is deliberately no startup-validation hook here. Injecting one would mean
 * `SessionState` depends on an API client, which depends on the HTTP client, which depends on
 * `SessionState::logout` — a DI cycle. Apps that verify a session at startup (adminApp resumes it
 * via the refresh cookie) call their own validator from the app root while [status] is
 * [SessionStatus.CHECKING], and pass `onUnauthorized` to the HTTP client as a *lambda* so the
 * lookup is deferred to 401-time.
 */
class SessionState(
    private val tokenStore: TokenStore,
    startupPolicy: StartupPolicy,
) {
    var status by mutableStateOf(
        when (startupPolicy) {
            StartupPolicy.WIPE -> {
                tokenStore.clear()
                SessionStatus.UNAUTHENTICATED
            }
            StartupPolicy.RESUME_VIA_REFRESH_COOKIE -> SessionStatus.CHECKING
        },
    )
        private set

    /**
     * Incremented whenever the server reports a fresh capability set — a login, or a silent token
     * refresh.
     *
     * Compose state, so reading it in a `remember` key makes that memo recompute when the answer
     * changes. A refresh does NOT move [status] (only a failed one signs out), so before this
     * existed a gate memoised on the token store never recomputed and an operator kept the sidebar
     * their old group had.
     */
    var capabilityEpoch by mutableStateOf(0)
        private set

    /** A fresh login, or a verified startup session, succeeded. */
    fun markLoggedIn() {
        capabilityEpoch++
        status = SessionStatus.AUTHENTICATED
    }

    /** A silent token refresh brought a new capability set with it. */
    fun onCapabilitiesRefreshed() {
        capabilityEpoch++
    }

    /**
     * Explicit logout, or a failed token refresh. Idempotent: several requests can 401 at
     * once and each failed refresh calls this, so a no-op when already signed out keeps a
     * refresh storm from wiping a newer session or looping.
     */
    fun logout() {
        if (status == SessionStatus.UNAUTHENTICATED) return
        tokenStore.clear()
        status = SessionStatus.UNAUTHENTICATED
    }
}
