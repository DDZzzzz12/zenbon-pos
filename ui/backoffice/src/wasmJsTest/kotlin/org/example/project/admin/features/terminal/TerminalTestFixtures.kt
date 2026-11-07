package org.example.project.admin.features.terminal

import org.example.project.core.domain.dto.terminal.TerminalDto

object TerminalTestFixtures {
    fun summary(
        id: Long,
        active: Boolean = true,
        tse: Boolean = false,
        fiscal: Boolean = true,
        name: String? = null,
        pairingCodeExpiresAt: String? = null,
        bound: Boolean = false,
        role: String = "CASHIER",
        clientKind: String = "NATIVE",
    ) = TerminalDto.TerminalSummaryDto(
        id = id,
        terminalId = "T00$id",
        terminalBrand = "Android",
        terminalModel = "Pixel",
        isActive = active,
        tseClientRegistered = tse,
        lastSeenAt = "2026-07-26T10:00:00Z",
        role = role,
        fiscal = fiscal,
        name = name,
        pairingCodeExpiresAt = pairingCodeExpiresAt,
        bound = bound,
        clientKind = clientKind,
    )

    fun response(id: Long) =
        TerminalDto.TerminalResponseDto(
            id = id,
            deviceId = "d$id",
            terminalId = "T00$id",
            terminalBrand = "Android",
            terminalModel = "Pixel",
            terminalSerialNr = null,
            terminalSwBrand = "POS",
            terminalSwVersion = "1",
            isActive = true,
            tseClientRegistered = false,
            registeredAt = "2026-07-26T10:00:00Z",
            lastSeenAt = "2026-07-26T10:00:00Z",
            role = "CASHIER",
            fiscal = true,
        )

    fun code(
        terminalDbId: Long,
        code: String,
        expiresAt: String = "2026-07-26T10:05:00Z",
    ) = TerminalDto.PairingCodeResponseDto(
        terminalDbId = terminalDbId,
        terminalId = "T00$terminalDbId",
        code = code,
        expiresAt = expiresAt,
    )
}
