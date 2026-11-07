package org.example.project.core.data.remote

import org.example.project.core.domain.dto.terminal.TerminalDto

/**
 * API client for terminal registration, provisioning, and device pairing.
 *
 * Shared by the POS device client (composeApp) and the web admin panel (adminApp);
 * bound over a module-specific [org.example.project.core.network.HttpCall].
 *
 * @since 1.0.0
 */
interface TerminalApiClient {
    suspend fun registerTerminal(request: TerminalDto.RegisterTerminalRequestDto): TerminalDto.TerminalResponseDto

    suspend fun getCurrentTerminal(): TerminalDto.TerminalResponseDto

    /**
     * Device-token self lookup used by the launch gate before any employee login. Sent on the device
     * credential ONLY, so a 401 here is always the device's verdict, never a person's session.
     */
    suspend fun getCurrentTerminalByDevice(): TerminalDto.TerminalResponseDto

    /**
     * Device-token exchange for a login-less ORDER_STATION: `POST /api/v1/terminals/session`, no
     * JWT, no body. Answers the station JWT plus the masked capability set; 409 unpaired, 403
     * deactivated / non-fiscal / no shell for the role, 424 unregistered under NORMAL TSE mode.
     */
    suspend fun openStationSession(): TerminalDto.StationSessionResponseDto

    suspend fun getAllTerminals(): List<TerminalDto.TerminalSummaryDto>

    /** Edits a terminal's name, location and role. A null field is left unchanged. */
    suspend fun updateTerminal(
        id: Long,
        request: TerminalDto.UpdateTerminalRequestDto,
    ): TerminalDto.TerminalResponseDto

    suspend fun deactivateTerminal(id: Long)

    suspend fun reactivateTerminal(id: Long): TerminalDto.TerminalSummaryDto

    suspend fun registerTerminalTse(id: Long): TerminalDto.TerminalSummaryDto

    /** `POST /{id}/tse-deregister` — removes a retired (deactivated, unbound) terminal's client from the TSE. */
    suspend fun deregisterTerminalTse(id: Long): TerminalDto.TerminalSummaryDto

    suspend fun syncTseStatus(): List<TerminalDto.TerminalSummaryDto>

    /** Public pairing call — binds this device to a terminal via a pairing code and returns the issued device token. No JWT. */
    suspend fun pairDevice(request: TerminalDto.PairDeviceRequestDto): TerminalDto.PairDeviceResponseDto

    /** Manager action — provisions a new unbound terminal with a chosen role/fiscal flag. */
    suspend fun createTerminal(request: TerminalDto.CreateTerminalRequestDto): TerminalDto.TerminalResponseDto

    /**
     * Manager action — issues a single-use pairing code (+ QR payload) for the given terminal. With
     * [registerTseClient] the device's pairing also registers the terminal's TSE client (423
     * `TSE_ADMIN_LOCKED` when that needs the Admin sitting and it is locked).
     */
    suspend fun generatePairingCode(
        id: Long,
        registerTseClient: Boolean = false,
    ): TerminalDto.PairingCodeResponseDto

    /** Manager read — whether a pairing code may currently ask for TSE registration (TSE NORMAL + commissioned). */
    suspend fun getTsePairingStatus(): TerminalDto.TsePairingStatusDto

    /** Manager action — revokes the device binding; the next request from the old device 409s. */
    suspend fun revokeBinding(id: Long): TerminalDto.TerminalSummaryDto
}
