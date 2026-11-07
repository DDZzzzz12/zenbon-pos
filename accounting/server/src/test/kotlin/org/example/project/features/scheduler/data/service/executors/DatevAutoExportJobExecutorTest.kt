package org.example.project.features.scheduler.data.service.executors

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.dto.datevexportlog.CatchupFailureDto
import org.example.project.core.domain.dto.datevexportlog.CatchupPeriodDto
import org.example.project.core.domain.dto.datevexportlog.CatchupPreviewDto
import org.example.project.core.domain.dto.datevexportlog.CatchupResultDto
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.enums.setting.DatevAutoCadence
import org.example.project.core.domain.enums.setting.DatevExportCadence
import org.example.project.features.datevexportlog.domain.models.DatevExportType
import org.example.project.features.datevexportlog.domain.service.DatevExportCatchupService
import org.example.project.features.scheduler.domain.service.JobPartiallyFailedException
import org.example.project.features.setup.domain.service.ConfigurationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFailsWith

class DatevAutoExportJobExecutorTest {
    private val systemUserId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private fun stubConfig(
        kassenbuch: DatevAutoCadence,
        ap: DatevAutoCadence,
        buch: DatevAutoCadence,
    ): ConfigurationService =
        object : ConfigurationService {
            override suspend fun getDatevXmlConfig(shopId: Long) =
                ShopConfigurationDto.DatevXmlConfigResponseDto(
                    shopId = shopId,
                    kassenName = "K",
                    exportPath = "/tmp",
                    enabled = true,
                    kassenbuchCadence = kassenbuch,
                    apLedgerCadence = ap,
                    buchungsstapelCadence = buch,
                )

            override suspend fun updateDatevXmlConfig(
                shopId: Long,
                request: ShopConfigurationDto.UpdateDatevXmlConfigRequestDto,
            ): ShopConfigurationDto.DatevXmlConfigResponseDto = error("not used")

            override suspend fun getBackupConfig(shopId: Long): BackupRestoreDto.BackupConfigDto = error("not used")

            override suspend fun touchLastBackupAt(
                shopId: Long,
                at: String,
            ) = error("not used")

            override suspend fun recordBackupCopy(
                shopId: Long,
                at: String?,
                status: org.example.project.core.domain.enums.backup.BackupCopyStatus,
                error: String?,
            ) = error("not used")
        }

    @Test
    fun `executes only types whose cadence is not OFF and aggregates results`() =
        runBlocking {
            val calls = mutableListOf<Pair<DatevExportType, DatevExportCadence>>()
            val catchup =
                object : DatevExportCatchupService {
                    override suspend fun preview(
                        type: DatevExportType,
                        cadence: DatevExportCadence,
                    ): CatchupPreviewDto = error("unused")

                    override suspend fun execute(
                        type: DatevExportType,
                        cadence: DatevExportCadence,
                        exportedByUserId: UUID,
                    ): CatchupResultDto {
                        calls += type to cadence
                        return CatchupResultDto(exported = emptyList(), failed = emptyList())
                    }
                }
            val config =
                stubConfig(
                    kassenbuch = DatevAutoCadence.MONTHLY,
                    ap = DatevAutoCadence.OFF,
                    buch = DatevAutoCadence.WEEKLY,
                )
            val executor = DatevAutoExportJobExecutor(catchup, config, systemUserId)

            val result = executor.execute("{}")

            assertEquals(2, calls.size, "Expected exactly 2 catchup invocations, got: $calls")
            assertEquals(
                listOf(DatevExportType.KASSENBUCH, DatevExportType.BUCHUNGSSTAPEL),
                calls.map { it.first },
            )
            assertTrue(
                result.contains("AP_LEDGER: skipped(OFF)"),
                "Result should mention AP_LEDGER skip, got: $result",
            )
        }

    private fun catchupAnswering(answer: (DatevExportType) -> CatchupResultDto): DatevExportCatchupService =
        object : DatevExportCatchupService {
            override suspend fun preview(
                type: DatevExportType,
                cadence: DatevExportCadence,
            ): CatchupPreviewDto = error("unused")

            override suspend fun execute(
                type: DatevExportType,
                cadence: DatevExportCadence,
                exportedByUserId: UUID,
            ): CatchupResultDto = answer(type)
        }

    private val allMonthly =
        stubConfig(
            kassenbuch = DatevAutoCadence.MONTHLY,
            ap = DatevAutoCadence.MONTHLY,
            buch = DatevAutoCadence.MONTHLY,
        )

    /**
     * admin16 NOTE-7: a type that threw was folded into the result text and the run recorded as
     * SUCCESS. The other types still run, but the run as a whole now fails, naming what failed.
     */
    @Test
    fun `a type that throws does not block the others but fails the run`() =
        runBlocking {
            val attempted = mutableListOf<DatevExportType>()
            val catchup =
                catchupAnswering { type ->
                    attempted += type
                    if (type == DatevExportType.AP_LEDGER) error("boom")
                    CatchupResultDto(exported = emptyList(), failed = emptyList())
                }
            val executor = DatevAutoExportJobExecutor(catchup, allMonthly, systemUserId)

            val failure = assertFailsWith<JobPartiallyFailedException> { executor.execute("{}") }

            assertEquals(
                listOf(DatevExportType.KASSENBUCH, DatevExportType.AP_LEDGER, DatevExportType.BUCHUNGSSTAPEL),
                attempted,
                "a failing type does not stop the ones after it",
            )
            assertEquals(listOf("AP_LEDGER: boom"), failure.failedParts)
            assertTrue(failure.resultMessage.contains("AP_LEDGER: failed(boom)"), failure.resultMessage)
            assertTrue(failure.resultMessage.contains("BUCHUNGSSTAPEL: 0 periods"), failure.resultMessage)
        }

    @Test
    fun `a failed or deferred period fails the run and is listed`() =
        runBlocking {
            val catchup =
                catchupAnswering { type ->
                    if (type != DatevExportType.KASSENBUCH) {
                        CatchupResultDto(exported = emptyList(), failed = emptyList())
                    } else {
                        CatchupResultDto(
                            exported = emptyList(),
                            failed = listOf(CatchupFailureDto("2026-08", "2026-08-01T00:00", "2026-09-01T00:00", "disk full")),
                            deferred = listOf(CatchupPeriodDto("2026-09-01T00:00", "2026-10-01T00:00", "2026-09")),
                        )
                    }
                }
            val executor = DatevAutoExportJobExecutor(catchup, allMonthly, systemUserId)

            val failure = assertFailsWith<JobPartiallyFailedException> { executor.execute("{}") }

            assertEquals(
                listOf(
                    "KASSENBUCH 2026-08: disk full",
                    "KASSENBUCH 2026-09: not attempted (an earlier period failed)",
                ),
                failure.failedParts,
            )
            assertTrue(failure.resultMessage.contains("KASSENBUCH: 0 periods, 1 failed, 1 deferred"), failure.resultMessage)
        }

    @Test
    fun `a clean run returns its summary`() =
        runBlocking {
            val catchup = catchupAnswering { CatchupResultDto(exported = emptyList(), failed = emptyList()) }
            val executor = DatevAutoExportJobExecutor(catchup, allMonthly, systemUserId)

            assertEquals(
                "KASSENBUCH: 0 periods, AP_LEDGER: 0 periods, BUCHUNGSSTAPEL: 0 periods",
                executor.execute("{}"),
            )
        }
}
