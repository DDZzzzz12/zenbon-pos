/**
 * Delegating implementation of [TseAdminProvider] that enables hot-swapping
 * the underlying concrete provider at runtime.
 *
 * Holds a [Volatile] reference to the current delegate and forwards all method calls.
 * When the TSE connection mode changes (REMOTE ↔ LOCAL), [switchDelegate] is called
 * to install the new provider without server restart.
 *
 * @see org.example.project.features.tse.domain.provider.TseAdminProvider
 * @since 1.0.0
 */
package org.example.project.features.tse.data.provider

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.features.tse.domain.provider.TseAdminProvider
import org.slf4j.LoggerFactory

class DelegatingTseAdminProvider(
    initial: TseAdminProvider,
) : TseAdminProvider {
    private val logger = LoggerFactory.getLogger(DelegatingTseAdminProvider::class.java)

    @Volatile
    private var delegate: TseAdminProvider = initial

    /**
     * Swaps the underlying provider implementation.
     *
     * @param newDelegate The new concrete TseAdminProvider to delegate to
     */
    @Synchronized
    fun switchDelegate(newDelegate: TseAdminProvider) {
        val old = delegate
        delegate = newDelegate
        logger.info("TseAdminProvider switched from ${old::class.simpleName} to ${newDelegate::class.simpleName}")
    }

    override suspend fun getStatus(): TseDto.StatusInfoDto = delegate.getStatus()

    override suspend fun healthCheck(): TseDto.HealthCheckResultDto = delegate.healthCheck()

    override suspend fun ping(): Boolean = delegate.ping()

    override suspend fun status(): TseDto.StatusInfoDto = delegate.status()

    override suspend fun getInfo(): TseDto.TseInfoDto = delegate.getInfo()

    override suspend fun flashHealth(): TseDto.FlashHealthResultDto = delegate.flashHealth()

    override suspend fun setup(
        deviceId: String,
        credentials: TseDto.SetupCredentialsRequestDto,
    ): TseDto.AdminResultDto = delegate.setup(deviceId, credentials)

    override suspend fun factoryReset(): TseDto.AdminResultDto = delegate.factoryReset()

    override suspend fun selfTest(clientId: String?): TseDto.SelfTestResultDto = delegate.selfTest(clientId)

    override suspend fun forceSelfTest(clientId: String?): TseDto.SelfTestResultDto = delegate.forceSelfTest(clientId)

    override suspend fun updateTime(): TseDto.TimeUpdateResultDto = delegate.updateTime()

    override suspend fun registerClient(clientId: String): TseDto.ClientRegistrationResultDto = delegate.registerClient(clientId)

    override suspend fun deregisterClient(clientId: String): TseDto.AdminResultDto = delegate.deregisterClient(clientId)

    override suspend fun listClients(): TseDto.ClientListResultDto = delegate.listClients()

    override suspend fun listTransactions(clientId: String?): TseDto.TransactionListResultDto = delegate.listTransactions(clientId)

    override suspend fun lastTransaction(clientId: String?): TseDto.LastTransactionResultDto = delegate.lastTransaction(clientId)

    override suspend fun exportLocal(outputPath: String): TseDto.LocalExportResultDto = delegate.exportLocal(outputPath)

    override suspend fun exportFull(outputPath: String): TseDto.ExportResultDto = delegate.exportFull(outputPath)

    override suspend fun exportIncremental(
        outputPath: String,
        lastState: String?,
        maxExportSize: Long,
    ): TseDto.ExportResultDto = delegate.exportIncremental(outputPath, lastState, maxExportSize)

    override suspend fun exportTimeFiltered(
        outputPath: String,
        startDate: Long,
        endDate: Long,
        clientId: String?,
    ): TseDto.ExportResultDto = delegate.exportTimeFiltered(outputPath, startDate, endDate, clientId)

    override suspend fun exportTransactionFiltered(
        outputPath: String,
        startTransaction: Long,
        endTransaction: Long,
        clientId: String?,
    ): TseDto.ExportResultDto = delegate.exportTransactionFiltered(outputPath, startTransaction, endTransaction, clientId)

    override suspend fun getExportSize(lastState: String?): TseDto.ExportSizeDto = delegate.getExportSize(lastState)

    override suspend fun deleteExportedData(outputPath: String): TseDto.ExportAndDeleteResultDto = delegate.deleteExportedData(outputPath)

    override suspend fun unblockUser(
        puk: String,
        userId: String?,
        newPin: String?,
    ): TseDto.AdminResultDto = delegate.unblockUser(puk, userId, newPin)

    override suspend fun changePin(
        currentPin: String,
        newPin: String,
        userId: String?,
    ): TseDto.AdminResultDto = delegate.changePin(currentPin, newPin, userId)

    override suspend fun enableCtss(): TseDto.AdminResultDto = delegate.enableCtss()

    override suspend fun disableCtss(): TseDto.AdminResultDto = delegate.disableCtss()

    override suspend fun checkFirmwareUpdate(): TseDto.FirmwareUpdateCheckResultDto = delegate.checkFirmwareUpdate()

    override suspend fun applyBundledFirmwareUpdate(): TseDto.FirmwareApplyResultDto = delegate.applyBundledFirmwareUpdate()

    override suspend fun decommission(): TseDto.AdminResultDto = delegate.decommission()

    override suspend fun disableExportIfCspTestFails(): TseDto.AdminResultDto = delegate.disableExportIfCspTestFails()

    override suspend fun browseEntries(request: TseDto.LogEntryBrowseRequestDto): TseDto.LogEntryBrowseResultDto = delegate.browseEntries(request)

    override suspend fun exportLcm(): TseDto.LcmExportResultDto = delegate.exportLcm()

    override suspend fun restartServer(): TseDto.AdminResultDto = delegate.restartServer()

    override suspend fun logoutAdmin(): TseDto.AdminResultDto = delegate.logoutAdmin()

    override suspend fun refreshConfig() = delegate.refreshConfig()

    override suspend fun setMode(
        mode: String,
        clientId: String,
    ): Boolean = delegate.setMode(mode, clientId)

    override suspend fun isInitialized(): Boolean = delegate.isInitialized()
}
