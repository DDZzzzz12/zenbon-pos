package org.example.project.features.terminal.domain.mapper

import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.features.terminal.domain.models.Terminal

/**
 * Extension function to convert Terminal entity to TerminalResponseDto.
 */
fun Terminal.toResponseDto(pairingCodeExpiresAt: String? = null): TerminalDto.TerminalResponseDto =
    TerminalDto.TerminalResponseDto(
        id = this.id.value,
        deviceId = this.deviceId ?: "",
        terminalId = this.clientId,
        terminalBrand = this.terminalBrand,
        terminalModel = this.terminalModel,
        terminalSerialNr = this.terminalSerialNr,
        terminalSwBrand = this.terminalSwBrand,
        terminalSwVersion = this.terminalSwVersion,
        isActive = this.isActive,
        tseClientRegistered = this.tseClientRegistered,
        registeredAt = this.registeredAt.toString(),
        lastSeenAt = this.lastSeenAt.toString(),
        role = this.role,
        fiscal = this.fiscal,
        name = this.name,
        location = this.location,
        pairingCodeExpiresAt = pairingCodeExpiresAt,
        clientKind = this.clientKind,
    )

/**
 * Extension function to convert Terminal entity to TerminalSummaryDto.
 */
fun Terminal.toSummaryDto(pairingCodeExpiresAt: String? = null): TerminalDto.TerminalSummaryDto =
    TerminalDto.TerminalSummaryDto(
        id = this.id.value,
        terminalId = this.clientId,
        terminalBrand = this.terminalBrand,
        terminalModel = this.terminalModel,
        isActive = this.isActive,
        tseClientRegistered = this.tseClientRegistered,
        lastSeenAt = this.lastSeenAt.toString(),
        role = this.role,
        fiscal = this.fiscal,
        name = this.name,
        location = this.location,
        pairingCodeExpiresAt = pairingCodeExpiresAt,
        clientKind = this.clientKind,
        bound = this.deviceId != null,
    )
