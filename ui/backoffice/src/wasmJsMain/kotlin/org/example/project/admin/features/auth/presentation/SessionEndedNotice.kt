package org.example.project.admin.features.auth.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.example.project.core.auth.SessionStatus

/**
 * Whether the login screen should explain that the previous session was ended *for* the operator
 * (an expired or revoked session) rather than by them. A failed startup resume never counts: the
 * refresh token is an HttpOnly cookie the page cannot see, so a visitor who never signed in looks
 * exactly like one whose cookie expired.
 *
 * A user-initiated sign-out never calls [markEnded], so it shows nothing. The flag lives until the
 * next sign-in attempt clears it, so the operator reads the reason once and it does not linger
 * across a successful login.
 */
class SessionEndedNotice {
    var pending by mutableStateOf(false)
        private set

    fun markEnded() {
        pending = true
    }

    fun clear() {
        pending = false
    }

    /**
     * Marks the notice only when a session the page knew about was in play ([SessionStatus.AUTHENTICATED]).
     * Several requests can 401 at once and each failed refresh signs out; once the login screen is up a
     * further rejection (a wrong password, say) is not "a session that ended". [SessionStatus.CHECKING]
     * is the startup resume through the cookie: with no cookie it always fails, so it says nothing.
     */
    fun markEndedFrom(statusBeforeSignOut: SessionStatus) {
        if (statusBeforeSignOut == SessionStatus.AUTHENTICATED) markEnded()
    }
}
