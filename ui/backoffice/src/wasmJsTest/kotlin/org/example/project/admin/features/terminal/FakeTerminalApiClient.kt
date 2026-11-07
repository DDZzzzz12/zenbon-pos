package org.example.project.admin.features.terminal

import kotlinx.coroutines.CompletableDeferred
import org.example.project.core.data.remote.TerminalApiClient
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.enums.setup.TseMode

/**
 * Canonical adminApp `wasmJsTest` double for the shared 11-method [TerminalApiClient] (RB0).
 * Throws-hook style: `*Result` (or a default) is returned unless the matching `*Error` hook is
 * set, in which case it is invoked to throw. Shared by RB2 (Terminals) + RB3 (first-terminal);
 * keep the field names stable across both plans.
 */
class FakeTerminalApiClient : TerminalApiClient {
    private val defaultResponse = TerminalTestFixtures.response(0)
    private val defaultSummary = TerminalTestFixtures.summary(0)

    // getAllTerminals
    var terminals: List<TerminalDto.TerminalSummaryDto> = emptyList()
    var getAllTerminalsError: (() -> Nothing)? = null
    var getAllTerminalsCallCount: Int = 0

    // createTerminal
    var createResult: TerminalDto.TerminalResponseDto? = null
    var createError: (() -> Nothing)? = null
    var createTerminalCallCount: Int = 0
    var lastCreateRequest: TerminalDto.CreateTerminalRequestDto? = null

    // generatePairingCode
    var pairingCodeResult: TerminalDto.PairingCodeResponseDto? = null
    var pairingCodeError: (() -> Nothing)? = null
    var generatePairingCodeCallCount: Int = 0
    var lastPairingCodeId: Long? = null
    var lastPairingCodeRegisterTse: Boolean? = null

    // getTsePairingStatus — unavailable by default, so a flow that does not care issues plain codes.
    var tsePairingStatus: TerminalDto.TsePairingStatusDto =
        TerminalDto.TsePairingStatusDto(mode = TseMode.NORMAL, initialized = false, available = false)
    var tsePairingStatusError: (() -> Nothing)? = null
    var tsePairingStatusCallCount: Int = 0

    // revokeBinding
    var revokeResult: TerminalDto.TerminalSummaryDto? = null
    var revokeError: (() -> Nothing)? = null
    var revokeBindingCallCount: Int = 0
    var lastRevokeId: Long? = null

    // registerTerminalTse
    var registerResult: TerminalDto.TerminalSummaryDto? = null
    var registerError: (() -> Nothing)? = null
    var registerCallCount: Int = 0
    var lastRegisterId: Long? = null

    /** When set, [registerTerminalTse] suspends until this completes — holds a call "in flight". */
    var registerGate: CompletableDeferred<Unit>? = null

    // deregisterTerminalTse
    var deregisterError: (() -> Nothing)? = null
    var deregisterCallCount: Int = 0
    var lastDeregisterId: Long? = null

    override suspend fun getAllTerminals(): List<TerminalDto.TerminalSummaryDto> {
        getAllTerminalsCallCount++
        getAllTerminalsError?.let { it() }
        return terminals
    }

    override suspend fun createTerminal(request: TerminalDto.CreateTerminalRequestDto): TerminalDto.TerminalResponseDto {
        createTerminalCallCount++
        lastCreateRequest = request
        createError?.let { it() }
        return createResult ?: defaultResponse
    }

    override suspend fun generatePairingCode(
        id: Long,
        registerTseClient: Boolean,
    ): TerminalDto.PairingCodeResponseDto {
        generatePairingCodeCallCount++
        lastPairingCodeId = id
        lastPairingCodeRegisterTse = registerTseClient
        pairingCodeError?.let { it() }
        return pairingCodeResult ?: TerminalTestFixtures.code(id, "000000")
    }

    override suspend fun getTsePairingStatus(): TerminalDto.TsePairingStatusDto {
        tsePairingStatusCallCount++
        tsePairingStatusError?.let { it() }
        return tsePairingStatus
    }

    override suspend fun revokeBinding(id: Long): TerminalDto.TerminalSummaryDto {
        revokeBindingCallCount++
        lastRevokeId = id
        revokeError?.let { it() }
        return revokeResult ?: defaultSummary
    }

    override suspend fun registerTerminalTse(id: Long): TerminalDto.TerminalSummaryDto {
        registerCallCount++
        lastRegisterId = id
        registerGate?.await()
        registerError?.let { it() }
        return registerResult ?: defaultSummary
    }

    override suspend fun deregisterTerminalTse(id: Long): TerminalDto.TerminalSummaryDto {
        deregisterCallCount++
        lastDeregisterId = id
        deregisterError?.let { it() }
        return defaultSummary
    }

    // --- remaining interface members are unused by the RB2/RB3 tests ---
    override suspend fun registerTerminal(request: TerminalDto.RegisterTerminalRequestDto): TerminalDto.TerminalResponseDto = defaultResponse

    override suspend fun getCurrentTerminal(): TerminalDto.TerminalResponseDto = defaultResponse

    override suspend fun getCurrentTerminalByDevice(): TerminalDto.TerminalResponseDto = defaultResponse

    override suspend fun openStationSession(): TerminalDto.StationSessionResponseDto =
        error("openStationSession is a till call; adminApp never opens a station session")

    /** Records what the screen asked to save, so a test can assert the request it built. */
    var lastUpdate: Pair<Long, TerminalDto.UpdateTerminalRequestDto>? = null
    var updateError: Throwable? = null

    override suspend fun updateTerminal(
        id: Long,
        request: TerminalDto.UpdateTerminalRequestDto,
    ): TerminalDto.TerminalResponseDto {
        lastUpdate = id to request
        updateError?.let { throw it }
        return defaultResponse
    }

    var deactivateCallCount: Int = 0
    var reactivateCallCount: Int = 0

    override suspend fun deactivateTerminal(id: Long) {
        deactivateCallCount++
    }

    override suspend fun reactivateTerminal(id: Long): TerminalDto.TerminalSummaryDto {
        reactivateCallCount++
        return defaultSummary
    }

    override suspend fun syncTseStatus(): List<TerminalDto.TerminalSummaryDto> = terminals

    override suspend fun pairDevice(request: TerminalDto.PairDeviceRequestDto): TerminalDto.PairDeviceResponseDto =
        TerminalDto.PairDeviceResponseDto(terminal = defaultResponse, deviceToken = "tok")
}
