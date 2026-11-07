package org.example.project.testutils.fakes

import org.example.project.core.data.remote.TerminalApiClient
import org.example.project.core.domain.dto.terminal.TerminalDto

/**
 * Shared fake [TerminalApiClient] for launch/auth flow tests. Extracted from
 * `LaunchViewModelTest` so `LaunchCoordinatorTest` can reuse the same fake body.
 */
class FakeLaunchTerminalApiClient : TerminalApiClient {
    var terminalByDeviceResult: (suspend () -> TerminalDto.TerminalResponseDto)? = null

    override suspend fun getCurrentTerminalByDevice(): TerminalDto.TerminalResponseDto = terminalByDeviceResult!!.invoke()

    var stationSessionResult: (suspend () -> TerminalDto.StationSessionResponseDto)? = null

    override suspend fun openStationSession(): TerminalDto.StationSessionResponseDto = stationSessionResult!!.invoke()

    override suspend fun updateTerminal(
        id: Long,
        request: TerminalDto.UpdateTerminalRequestDto,
    ): TerminalDto.TerminalResponseDto = TODO("not used by this test")

    override suspend fun registerTerminal(request: TerminalDto.RegisterTerminalRequestDto): TerminalDto.TerminalResponseDto = TODO()

    override suspend fun getCurrentTerminal(): TerminalDto.TerminalResponseDto = TODO()

    override suspend fun getAllTerminals(): List<TerminalDto.TerminalSummaryDto> = TODO()

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
