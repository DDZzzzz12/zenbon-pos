package org.example.project.features.terminal.domain.service

import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.features.terminal.data.service.TerminalCapability

/**
 * Exchanges a paired device's principal for a station session (order-station shell, 2026-09-06).
 *
 * Lives in pos-server rather than next to [TerminalService] in core-data because minting the JWT
 * needs `JwtConfig`, which core-data cannot see — the same split as `RefreshTokenService`.
 */
interface StationSessionService {
    /**
     * Mints the station JWT for [cap], the terminal the device token proved.
     *
     * Throws `PosException` 403 (`AUTH_FORBIDDEN`, "no shell for role X") for any role but
     * ORDER_STATION — CASHIER included, because a till logs a person in and must not run as the
     * shared terminal-employee — and 500 if the `terminal-device` employee is not provisioned.
     */
    suspend fun openSession(cap: TerminalCapability): TerminalDto.StationSessionResponseDto
}
