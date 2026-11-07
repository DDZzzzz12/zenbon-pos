package org.example.project.admin.features.tse.presentation

import co.touchlab.kermit.Logger
import org.example.project.core.data.remote.TseSettingsApiClient
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException

/**
 * Admin-PIN control plane over the server TSE endpoints. Holds NO clientId and never signs:
 * every clientId-needing operation runs server-side. This gate only opens/closes the server's
 * in-memory admin sitting and lends the [runUnlockingIfNeeded] recovery to other admin flows.
 */
class TseAdminGate(
    private val tse: TseSettingsApiClient,
) {
    private val log = Logger.withTag("tse-admin-gate")

    /** Whether an admin sitting is currently live on the server. */
    suspend fun status(): Boolean = tse.adminSessionStatus().unlocked

    /** Hands [pin] to the server to start a sitting; returns whether it is now unlocked. */
    suspend fun unlock(pin: String): Boolean {
        val s = tse.unlockAdmin(pin)
        log.d { "admin unlock -> unlocked=${s.unlocked}" }
        return s.unlocked
    }

    /** Ends the sitting immediately. */
    suspend fun lock() {
        tse.lockAdmin()
        log.d { "admin sitting locked" }
    }

    /** Commissions the device with operator-supplied credentials (server keeps none). */
    suspend fun commission(credentials: TseDto.SetupCredentialsRequestDto): TseDto.AdminResultDto = tse.setup(credentials)

    /**
     * Runs [block]; if it is refused with [PosErrorCode.TSE_ADMIN_LOCKED] (server 423), asks
     * [providePin] for the Admin PIN, unlocks, and retries [block] exactly once. Returns the
     * block result. Rethrows the original 423 when the operator declines (null pin) or the PIN
     * does not open the sitting; rethrows any non-423 failure without retrying.
     *
     * RB2 (registerTerminalTse) and RB3 (back-office issuance) call this to recover from a
     * sitting that was locked when they attempted a fiscal registration.
     */
    suspend fun <T> runUnlockingIfNeeded(
        providePin: suspend () -> String?,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (e: PosApiException) {
            if (e.code != PosErrorCode.TSE_ADMIN_LOCKED) throw e
            log.d { "operation refused: TSE admin sitting locked — prompting for PIN" }
            val pin = providePin() ?: throw e
            if (!unlock(pin)) throw e
            block()
        }
}
