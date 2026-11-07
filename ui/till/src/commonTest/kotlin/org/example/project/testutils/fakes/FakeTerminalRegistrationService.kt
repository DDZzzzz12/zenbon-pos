package org.example.project.testutils.fakes

import org.example.project.core.data.domain.service.TerminalRegistrationService
import org.example.project.core.domain.dto.terminal.TerminalDto

class FakeTerminalRegistrationService : TerminalRegistrationService {
    var ensureRegisteredResult: TerminalDto.TerminalResponseDto =
        TerminalDto.TerminalResponseDto(
            id = 1L,
            deviceId = "test-device",
            terminalId = "T001",
            terminalBrand = "Test",
            terminalModel = "TestModel",
            terminalSerialNr = null,
            terminalSwBrand = "POS-KMP",
            terminalSwVersion = "1.0.0",
            isActive = true,
            tseClientRegistered = false,
            registeredAt = "2025-01-01T00:00:00",
            lastSeenAt = "2025-01-01T00:00:00",
        )
    var ensureRegisteredThrows: Throwable? = null
    var forceRegisterThrows: Throwable? = RuntimeException("server error")

    var registeredState = false
    var terminalIdValue: String? = null

    var ensureRegisteredCallCount = 0
    var forceRegisterCallCount = 0
    var clearRegistrationCallCount = 0

    override suspend fun ensureRegistered(): TerminalDto.TerminalResponseDto {
        ensureRegisteredCallCount++
        ensureRegisteredThrows?.let { throw it }
        return ensureRegisteredResult
    }

    override fun isRegistered(): Boolean = registeredState

    override fun getTerminalId(): String? = terminalIdValue

    override suspend fun forceRegister(): TerminalDto.TerminalResponseDto {
        forceRegisterCallCount++
        forceRegisterThrows?.let { throw it }
        return ensureRegisteredResult
    }

    override fun clearRegistration() {
        clearRegistrationCallCount++
        registeredState = false
        terminalIdValue = null
    }
}
