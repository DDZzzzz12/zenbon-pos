package org.example.project.ui.auth

/**
 * What an app does with a persisted session at startup.
 *
 * This is the client's session-security boundary, so it is stated explicitly rather than
 * left as a side effect somewhere in the login flow.
 */
enum class StartupPolicy {
    /**
     * adminApp. The refresh token is an HttpOnly cookie and nothing else about the session is
     * persisted, so there is nothing local to inspect: every page load starts
     * [org.example.project.core.auth.SessionStatus.CHECKING], and the app root's validation lets the
     * HTTP client's cookie refresh recover the session — or sign out when there is none.
     */
    RESUME_VIA_REFRESH_COOKIE,

    /**
     * composeApp. A cashier terminal requires a fresh login on every launch — tokens are
     * wiped when this class is constructed. Two reasons, both terminal-specific: every bill
     * and TSE signature is attributed to the logged-in employee, and the device-pairing gate
     * (`TERMINAL_NEEDS_PAIRING`) only fires during an explicit login, so a resumed session
     * would enter the POS without it.
     */
    WIPE,
}
