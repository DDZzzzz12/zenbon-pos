package org.example.project.features.settings.presentation.viewmodel

import org.example.project.core.data.remote.TerminalApiClient
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException

/**
 * The two terminal reads the TSE setup dialog makes: the terminal list and the caller's own terminal.
 * Everything else is unused.
 */
class FakeTerminalApi : TerminalApiClient {
    var terminals: List<TerminalDto.TerminalSummaryDto> = emptyList()

    /** When set, [getAllTerminals] refuses with it. */
    var listError: PosApiException? = null

    /** The caller's own terminal's client id; null answers 404, as the back office (a browser) gets. */
    var ownClientId: String? = null

    override suspend fun getAllTerminals(): List<TerminalDto.TerminalSummaryDto> {
        listError?.let { throw it }
        return terminals
    }

    override suspend fun getCurrentTerminal(): TerminalDto.TerminalResponseDto {
        val clientId =
            ownClientId ?: throw PosApiException(PosErrorCode.TERMINAL_NOT_FOUND, 404, "No terminal is registered for this device", null)
        return TerminalDto.TerminalResponseDto(
            id = 1,
            deviceId = "till-1",
            terminalId = clientId,
            terminalBrand = null,
            terminalModel = null,
            terminalSerialNr = null,
            terminalSwBrand = "POS-KMP",
            terminalSwVersion = null,
            isActive = true,
            registeredAt = "2026-10-04T10:00:00",
            lastSeenAt = "2026-10-04T10:00:00",
        )
    }

    override suspend fun registerTerminal(request: TerminalDto.RegisterTerminalRequestDto): TerminalDto.TerminalResponseDto = TODO()

    override suspend fun getCurrentTerminalByDevice(): TerminalDto.TerminalResponseDto = TODO()

    override suspend fun openStationSession(): TerminalDto.StationSessionResponseDto = TODO()

    override suspend fun updateTerminal(
        id: Long,
        request: TerminalDto.UpdateTerminalRequestDto,
    ): TerminalDto.TerminalResponseDto = TODO()

    override suspend fun deactivateTerminal(id: Long) = TODO()

    override suspend fun reactivateTerminal(id: Long): TerminalDto.TerminalSummaryDto = TODO()

    override suspend fun registerTerminalTse(id: Long): TerminalDto.TerminalSummaryDto = TODO()

    override suspend fun deregisterTerminalTse(id: Long): TerminalDto.TerminalSummaryDto = TODO()

    override suspend fun syncTseStatus(): List<TerminalDto.TerminalSummaryDto> = TODO()

    override suspend fun pairDevice(request: TerminalDto.PairDeviceRequestDto): TerminalDto.PairDeviceResponseDto = TODO()

    override suspend fun createTerminal(request: TerminalDto.CreateTerminalRequestDto): TerminalDto.TerminalResponseDto = TODO()

    override suspend fun generatePairingCode(
        id: Long,
        registerTseClient: Boolean,
    ): TerminalDto.PairingCodeResponseDto = TODO()

    override suspend fun getTsePairingStatus(): TerminalDto.TsePairingStatusDto = TODO()

    override suspend fun revokeBinding(id: Long): TerminalDto.TerminalSummaryDto = TODO()
}
