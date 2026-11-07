package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.tse.domain.provider.TseAdminProvider
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * H10 (audit 2026-07-13): a requested-but-failed TSE TAR export must fail the export
 * loudly — silently shipping a DSFinV-K package without TSE data made operators believe
 * the export was complete. `allowMissingTseTar=true` is the explicit opt-out.
 */
@DisplayName("DSFinV-K TSE TAR failure policy (H10)")
class DsfinvkTseTarPolicyTest {
    // No TseAdminProvider injected — every TAR fetch fails, like an unreachable TSE.
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            TestData.createZBonFullDay(shop, admin, nummer = 1L)
        }
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    @Test
    fun `includeTseTar without allowMissingTseTar fails loudly when the TSE export is unavailable`() {
        val ex =
            assertFailsWith<PosException> {
                runBlocking {
                    service.generateExport(
                        DsfinvkExportDto.ExportRequestDto(
                            startDate = "2000-01-01",
                            endDate = "2100-01-01",
                            includeTseTar = true,
                        ),
                    )
                }
            }
        assertTrue(ex.message!!.contains("TSE TAR export unavailable"), "got: ${ex.message}")
    }

    @Test
    fun `allowMissingTseTar explicitly ships without TSE data`() {
        val zip =
            runBlocking {
                service.generateExport(
                    DsfinvkExportDto.ExportRequestDto(
                        startDate = "2000-01-01",
                        endDate = "2100-01-01",
                        includeTseTar = true,
                        allowMissingTseTar = true,
                    ),
                )
            }
        assertTrue(zip.isNotEmpty(), "export must still be produced when explicitly allowed")
    }

    /**
     * The DSFinV-K package must ship the FULL unfiltered device TAR via exportFull, because
     * closings can cross midnight so no calendar window is ever exactly right (CONT-1).
     *
     * Originally this also guarded TSER-1 (round 3): pos-tse supplied a synthetic maintenance
     * clientId, and the time-filtered WormAPI export is client-filtered, so a filtered TAR
     * carried NO fiscal log messages. That cause has been removed — pos-tse now uses the real
     * Kasse — but the requirement stands on CONT-1 alone, and on TSE 2.0 filtered exports fail
     * outright anyway.
     */
    @Test
    fun `TAR is fetched via the FULL unfiltered export, never time-filtered`() {
        var fullCalled = false
        var timeFilteredCalled = false
        val provider =
            object : TseAdminProvider by unusedProvider() {
                override suspend fun exportFull(outputPath: String): TseDto.ExportResultDto {
                    fullCalled = true
                    val f = File.createTempFile("tse", ".tar").apply { writeBytes(byteArrayOf(1, 2, 3)) }
                    return TseDto.ExportResultDto(
                        success = true,
                        exportType = "FULL",
                        filePath = f.absolutePath,
                        fileName = "tse-export.tar",
                    )
                }

                override suspend fun exportTimeFiltered(
                    outputPath: String,
                    startDate: Long,
                    endDate: Long,
                    clientId: String?,
                ): TseDto.ExportResultDto {
                    timeFilteredCalled = true
                    error("must not be called")
                }
            }
        val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink(), tseAdminProvider = provider)
        runBlocking {
            service.generateExport(
                DsfinvkExportDto.ExportRequestDto(
                    startDate = "2000-01-01",
                    endDate = "2100-01-01",
                    includeTseTar = true,
                ),
            )
        }
        assertTrue(fullCalled, "DSFinV-K TAR must come from exportFull (unfiltered)")
        assertFalse(timeFilteredCalled, "exportTimeFiltered feeds the maintenance clientId (TSER-1)")
    }
}

/**
 * A TseAdminProvider whose every method throws — the base for `by` delegation in tests
 * that only care about a couple of overridden methods.
 */
private fun unusedProvider(): TseAdminProvider =
    object : TseAdminProvider {
        private fun no(): Nothing = throw NotImplementedError("unused in this test")

        override suspend fun getStatus(): TseDto.StatusInfoDto = no()

        override suspend fun healthCheck(): TseDto.HealthCheckResultDto = no()

        override suspend fun ping(): Boolean = no()

        override suspend fun status(): TseDto.StatusInfoDto = no()

        override suspend fun getInfo(): TseDto.TseInfoDto = no()

        override suspend fun flashHealth(): TseDto.FlashHealthResultDto = no()

        override suspend fun setup(
            deviceId: String,
            credentials: TseDto.SetupCredentialsRequestDto,
        ): TseDto.AdminResultDto = no()

        override suspend fun factoryReset(): TseDto.AdminResultDto = no()

        override suspend fun selfTest(clientId: String?): TseDto.SelfTestResultDto = no()

        override suspend fun forceSelfTest(clientId: String?): TseDto.SelfTestResultDto = no()

        override suspend fun updateTime(): TseDto.TimeUpdateResultDto = no()

        override suspend fun registerClient(clientId: String): TseDto.ClientRegistrationResultDto = no()

        override suspend fun deregisterClient(clientId: String): TseDto.AdminResultDto = no()

        override suspend fun listClients(): TseDto.ClientListResultDto = no()

        override suspend fun listTransactions(clientId: String?): TseDto.TransactionListResultDto = no()

        override suspend fun lastTransaction(clientId: String?): TseDto.LastTransactionResultDto = no()

        override suspend fun exportLocal(outputPath: String): TseDto.LocalExportResultDto = no()

        override suspend fun exportFull(outputPath: String): TseDto.ExportResultDto = no()

        override suspend fun exportIncremental(
            outputPath: String,
            lastState: String?,
            maxExportSize: Long,
        ): TseDto.ExportResultDto = no()

        override suspend fun exportTimeFiltered(
            outputPath: String,
            startDate: Long,
            endDate: Long,
            clientId: String?,
        ): TseDto.ExportResultDto = no()

        override suspend fun exportTransactionFiltered(
            outputPath: String,
            startTransaction: Long,
            endTransaction: Long,
            clientId: String?,
        ): TseDto.ExportResultDto = no()

        override suspend fun getExportSize(lastState: String?): TseDto.ExportSizeDto = no()

        override suspend fun deleteExportedData(outputPath: String): TseDto.ExportAndDeleteResultDto = no()

        override suspend fun unblockUser(
            puk: String,
            userId: String?,
            newPin: String?,
        ): TseDto.AdminResultDto = no()

        override suspend fun changePin(
            currentPin: String,
            newPin: String,
            userId: String?,
        ): TseDto.AdminResultDto = no()

        override suspend fun enableCtss(): TseDto.AdminResultDto = no()

        override suspend fun disableCtss(): TseDto.AdminResultDto = no()

        override suspend fun checkFirmwareUpdate(): TseDto.FirmwareUpdateCheckResultDto = no()

        override suspend fun applyBundledFirmwareUpdate(): TseDto.FirmwareApplyResultDto = no()

        override suspend fun decommission(): TseDto.AdminResultDto = no()

        override suspend fun disableExportIfCspTestFails(): TseDto.AdminResultDto = no()

        override suspend fun browseEntries(request: TseDto.LogEntryBrowseRequestDto): TseDto.LogEntryBrowseResultDto = no()

        override suspend fun exportLcm(): TseDto.LcmExportResultDto = no()

        override suspend fun restartServer(): TseDto.AdminResultDto = no()

        override suspend fun logoutAdmin(): TseDto.AdminResultDto = no()

        override suspend fun refreshConfig() = no()

        override suspend fun setMode(
            mode: String,
            clientId: String,
        ): Boolean = no()

        override suspend fun isInitialized(): Boolean = no()
    }
