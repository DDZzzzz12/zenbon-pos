/**
 * Service for managing terminal registration with the server.
 *
 * Handles automatic terminal registration after successful login
 * and provides methods for checking registration status.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.domain.service

import co.touchlab.kermit.Logger
import org.example.project.BuildKonfig
import org.example.project.core.data.database.LocalDatabaseManager
import org.example.project.core.data.remote.TerminalApiClient
import org.example.project.core.device.DeviceInfoProvider
import org.example.project.core.domain.dto.terminal.TerminalDto

/**
 * Service interface for terminal registration operations.
 *
 * Throwing methods (`ensureRegistered`, `forceRegister`) propagate
 * PosApiException/NetworkApiException; callers wrap in runCatchingApi.
 */
interface TerminalRegistrationService {
    suspend fun ensureRegistered(): TerminalDto.TerminalResponseDto

    fun isRegistered(): Boolean

    fun getTerminalId(): String?

    suspend fun forceRegister(): TerminalDto.TerminalResponseDto

    fun clearRegistration()
}

class TerminalRegistrationServiceImpl(
    private val terminalApiClient: TerminalApiClient,
    private val localDb: LocalDatabaseManager,
) : TerminalRegistrationService {
    private val logger = Logger.withTag("TerminalRegistration")

    override suspend fun ensureRegistered(): TerminalDto.TerminalResponseDto {
        val request = buildRegistrationRequest()
        logger.d { "Registering terminal with device ID: ${DeviceInfoProvider.getDeviceId()}" }
        val terminal = terminalApiClient.registerTerminal(request)
        localDb.setTerminalRegistered(terminal.terminalId, terminal.id)
        logger.i { "Terminal registered successfully: ${terminal.terminalId}" }
        return terminal
    }

    override fun isRegistered(): Boolean = localDb.isTerminalRegistered()

    override fun getTerminalId(): String? = localDb.getTerminalId()

    override suspend fun forceRegister(): TerminalDto.TerminalResponseDto {
        localDb.clearTerminalRegistration()
        return ensureRegistered()
    }

    override fun clearRegistration() {
        localDb.clearTerminalRegistration()
        logger.d { "Terminal registration cleared" }
    }

    private fun buildRegistrationRequest(): TerminalDto.RegisterTerminalRequestDto =
        TerminalDto.RegisterTerminalRequestDto(
            model = DeviceInfoProvider.getDeviceModel(),
            brand = DeviceInfoProvider.getPlatformBrand(),
            serialNr = null,
            swVersion = getAppVersion(),
        )

    private fun getAppVersion(): String = BuildKonfig.APP_VERSION
}
