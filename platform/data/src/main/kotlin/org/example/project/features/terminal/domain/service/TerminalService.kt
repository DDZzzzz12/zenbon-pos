/**
 * Service interface for Terminal business logic.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors;
 * the global StatusPages handler converts to application/problem+json.
 * Migrated from Result<T, Error> in Stage B Batch 11.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.terminal.domain.service

import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.enums.terminal.TerminalRole

/**
 * Interface defining terminal service operations.
 *
 * Provides business logic for terminal registration and management
 * with proper DTO transformation for API responses.
 */
interface TerminalService {
    /**
     * Registers a new terminal or updates existing one.
     *
     * @param employeeId ID of the employee performing the operation (for entry logging)
     * @param deviceId Unique device identifier from client header
     * @param request Registration request with device details
     * @return TerminalResponseDto for the registered/updated terminal
     */
    suspend fun registerOrUpdate(
        employeeId: String,
        deviceId: String,
        request: TerminalDto.RegisterTerminalRequestDto,
    ): TerminalDto.TerminalResponseDto

    /**
     * Provisions a new unbound terminal (no device attached yet).
     *
     * Admin/manager action. Generates the next TER-XXX clientId and makes no TSE call —
     * TSE registration happens later via pairing + the existing tse-register endpoint.
     *
     * @param employeeId ID of the employee performing the operation (for entry logging)
     * @param request Creation request with the terminal's name/role/fiscal override
     * @return TerminalResponseDto for the newly provisioned terminal
     */
    suspend fun createTerminal(
        employeeId: String,
        request: TerminalDto.CreateTerminalRequestDto,
    ): TerminalDto.TerminalResponseDto

    /**
     * Generates a fresh single-use pairing code for a terminal.
     *
     * Invalidates any previously outstanding code for the same terminal. With
     * [registerTseClient] false the code pairs without touching the TSE. With it true, pairing
     * registers the terminal's TSE client: the terminal must be fiscal and the TSE in NORMAL mode
     * and commissioned (else 409), and — unless the TSE already lists the client — the TSE admin
     * sitting must be unlocked (else 423 `TSE_ADMIN_LOCKED`); the code is then bound to that
     * [org.example.project.features.tse.domain.session.TseAdminSession] sitting and dies with it.
     *
     * @param employeeId ID of the employee performing the operation (for entry logging)
     * @param terminalDbId Database ID of the terminal to generate a code for
     * @param registerTseClient Whether pairing with this code must register the TSE client
     * @return PairingCodeResponseDto with the plaintext code and its expiry
     */
    suspend fun generatePairingCode(
        employeeId: String,
        terminalDbId: Long,
        registerTseClient: Boolean = false,
    ): TerminalDto.PairingCodeResponseDto

    /**
     * Whether pairing can register a TSE client right now: the TSE mode and whether the device is
     * commissioned. The back office reads it to default and enable its "register at the TSE" box.
     */
    suspend fun getTsePairingStatus(): TerminalDto.TsePairingStatusDto

    /**
     * Binds a physical device to a terminal using a pairing code. Public — authorized by the
     * code itself, not a user JWT. Issues a durable device token (returned once, plaintext);
     * only its hash is persisted.
     *
     * @param request Pairing code + device identity details
     * @return PairDeviceResponseDto with the terminal identity and the issued device token
     */
    suspend fun pairDevice(request: TerminalDto.PairDeviceRequestDto): TerminalDto.PairDeviceResponseDto

    /**
     * Revokes a terminal's device binding, dropping it back to needs-pairing.
     *
     * Also invalidates any outstanding pairing codes for the terminal. Does not (yet) guard
     * against open TSE transactions/PENDING bills or push an SSE notification — that lands in
     * Phase A4.
     *
     * @param employeeId ID of the employee performing the operation (for entry logging)
     * @param terminalDbId Database ID of the terminal to revoke
     * @return TerminalSummaryDto for the now-unbound terminal
     */
    suspend fun revokeBinding(
        employeeId: String,
        terminalDbId: Long,
    ): TerminalDto.TerminalSummaryDto

    /**
     * Gets terminal information by device ID.
     */
    suspend fun getTerminalByDeviceId(deviceId: String): TerminalDto.TerminalResponseDto

    /**
     * Gets the current terminal for a device-principal request.
     *
     * Used by the launch gate to validate a stored device token and read the terminal's
     * role without requiring an employee JWT login.
     */
    suspend fun getCurrentTerminalByDevice(deviceId: String): TerminalDto.TerminalResponseDto

    /**
     * Gets all registered terminals.
     */
    suspend fun getAllTerminals(): List<TerminalDto.TerminalSummaryDto>

    /**
     * Gets all active terminals.
     */
    suspend fun getActiveTerminals(): List<TerminalDto.TerminalSummaryDto>

    /**
     * Edits a terminal's name, location and role.
     *
     * `fiscal` is not editable here on purpose: DSFinV-K and the TSE client registration are built
     * on whether a terminal signs, so flipping it after the fact would leave already-signed receipts
     * pointing at a client that no longer claims to sign.
     *
     * @param employeeId ID of the employee performing the operation (for entry logging)
     * @param terminalId Database id of the terminal to edit
     * @param request Fields to change; a null field is left unchanged
     * @return the terminal as it now stands
     */
    suspend fun updateTerminal(
        employeeId: String,
        terminalId: Long,
        request: TerminalDto.UpdateTerminalRequestDto,
    ): TerminalDto.TerminalResponseDto

    /**
     * Deactivates a terminal.
     */
    suspend fun deactivateTerminal(
        employeeId: String,
        terminalId: Long,
    )

    /**
     * Updates last seen timestamp for a terminal.
     * Called automatically on each authenticated request.
     */
    suspend fun updateLastSeen(deviceId: String)

    /**
     * Records the client software version a device reports when it opens its notification stream,
     * keeping the terminal's `terminal_sw_version` (DSFinV-K TERMINAL_SW_VERSION) current after an
     * app update — the only refresh a login-less order station ever gets.
     *
     * Writes only when the version differs. Closings that already carry a Stammdaten snapshot keep
     * the version they were taken under. Best-effort and never throws, so it cannot refuse the
     * stream: a missing, blank or over-long version, or a device bound to no terminal, is a no-op.
     *
     * @param deviceId The physical device id from the verified token claims
     * @param reportedVersion The `X-App-Version` header value, or null when absent
     */
    suspend fun recordReportedSwVersion(
        deviceId: String,
        reportedVersion: String?,
    )

    /**
     * Registers a specific terminal's client ID with the TSE hardware.
     *
     * Looks up the terminal by database ID, checks TSE mode, and calls
     * the TSE admin provider to register the terminal's client ID.
     * Updates the `tseClientRegistered` flag on success.
     */
    suspend fun registerTerminalTse(terminalId: Long): TerminalDto.TerminalSummaryDto

    /**
     * Removes a retired terminal's client ID from the TSE, freeing a slot under the device's client
     * limit. Refused (409) unless the terminal is deactivated and has no bound device, has no
     * unfinished TSE work, and is not the last client a NORMAL-mode TSE still has; 423 while the
     * Admin sitting is locked. Clears `tseClientRegistered` and writes a `DEREGISTER_TSE_CLIENT`
     * audit row.
     */
    suspend fun deregisterTerminalTse(
        employeeId: String,
        terminalId: Long,
    ): TerminalDto.TerminalSummaryDto

    /**
     * Reactivates a previously deactivated terminal (sets isActive = true).
     *
     * Also re-verifies the terminal's TSE registration state (read-only) and
     * reconciles the `tseClientRegistered` flag. TSE re-verify is best-effort:
     * reactivation succeeds even if TSE is disabled or unreachable.
     */
    suspend fun reactivateTerminal(
        employeeId: String,
        terminalId: Long,
    ): TerminalDto.TerminalSummaryDto

    /**
     * Synchronizes TSE registration status for all terminals.
     *
     * Queries the TSE device for currently registered client IDs,
     * cross-references with all terminals in the database, and updates
     * the `tseClientRegistered` flags accordingly.
     */
    suspend fun syncTseRegistrationStatus(): List<TerminalDto.TerminalSummaryDto>

    /**
     * Drops every bound browser station whose role has just been switched off in the
     * `browser_stations` shop setting: pushes `terminal_session_terminated(BROWSER_DISABLED,
     * requiresRepairing = false)` to its device. Roles only added, or no change, push nothing. The
     * binding and TSE flags are untouched, so re-enabling the role lets the same device back in.
     * NATIVE terminals are never affected. Call after the new setting has been committed.
     */
    suspend fun onBrowserStationRolesChanged(
        previous: Set<TerminalRole>,
        current: Set<TerminalRole>,
    )
}
