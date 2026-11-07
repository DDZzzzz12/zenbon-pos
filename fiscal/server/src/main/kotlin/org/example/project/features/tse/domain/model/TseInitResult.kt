package org.example.project.features.tse.domain.model

/**
 * Outcome of LocalTseLifecycleManager.initialize(), surfaced synchronously so a mode change
 * tells the operator what actually happened. Distinguishes the safe outage cases (not located /
 * unreachable -> keep NORMAL, queue, Nachsignieren) from an AUTH/LOGIN failure (located + connected
 * but admin/time-admin config failed -> do NOT auto-retry; may require PUK unblock) and from a
 * device that is simply not commissioned yet.
 */
sealed interface TseInitResult {
    /** TSE configured and operational (NORMAL signing or SIMULATION). */
    data object Operational : TseInitResult

    /** Explicitly DISABLED by operator config — not an error. */
    data object Disabled : TseInitResult

    /** NORMAL requested but hardware not located or not connectable. Safe outage. */
    data class Unreachable(
        val reason: String,
    ) : TseInitResult

    /**
     * Hardware located + connected, but admin/time-admin login/config failed.
     * NOT auto-retried (PIN-lockout safety). [remainingRetries] is the real count from the TSE
     * when known (null = unknown); [locked] is true when the PIN is blocked (PUK required).
     */
    data class AuthFailed(
        val reason: String,
        val remainingRetries: Int?,
        val locked: Boolean,
    ) : TseInitResult

    /**
     * Hardware located + connected, but the TSE is still UNINITIALIZED: nothing to bring up until an
     * operator commissions it (POST /api/tse/setup). No login was attempted, so this is never an
     * authentication failure — reporting it as one sent operators to check a PIN that was fine.
     */
    data object NotCommissioned : TseInitResult

    /** Config invalid (validate() threw) before any hardware attempt. */
    data class InvalidConfig(
        val reason: String,
    ) : TseInitResult
}
