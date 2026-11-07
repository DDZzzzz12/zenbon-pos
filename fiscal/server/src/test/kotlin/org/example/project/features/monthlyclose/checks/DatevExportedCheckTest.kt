package org.example.project.features.monthlyclose.checks

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDetailCodes
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.core.domain.enums.setting.DatevExportCadence
import org.example.project.features.monthlyclose.data.service.checks.DatevExportedCheck
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DatevExportedCheckTest {
    private val check = DatevExportedCheck()

    private val periodStart = LocalDateTime.parse("2026-05-01T00:00:00")
    private val periodEnd = LocalDateTime.parse("2026-06-01T00:00:00")

    // -------------------------------------------------------------------------
    // MANUAL cadence
    // -------------------------------------------------------------------------

    @Test
    fun `MANUAL cadence returns HIDDEN regardless of logs`() {
        val result = check.run(periodStart, periodEnd, DatevExportCadence.MANUAL, exportLogs = emptyList())

        assertEquals(MonthlyCloseDto.ChecklistStatus.HIDDEN, result.status)
        assertEquals("datev_exported", result.id)
    }

    @Test
    fun `MANUAL cadence returns HIDDEN even when logs present`() {
        val result =
            check.run(
                periodStart,
                periodEnd,
                DatevExportCadence.MANUAL,
                exportLogs =
                    listOf(
                        makeLog("KASSENBUCH", "2026-05-01T00:00:00", "2026-06-01T00:00:00"),
                    ),
            )

        assertEquals(MonthlyCloseDto.ChecklistStatus.HIDDEN, result.status)
    }

    // -------------------------------------------------------------------------
    // MONTHLY cadence
    // -------------------------------------------------------------------------

    @Test
    fun `MONTHLY cadence PASSED when both KASSENBUCH and AP_LEDGER fully cover period`() {
        val logs =
            listOf(
                makeLog("KASSENBUCH", "2026-05-01T00:00:00", "2026-06-01T00:00:00"),
                makeLog("AP_LEDGER", "2026-05-01T00:00:00", "2026-06-01T00:00:00"),
            )
        val result = check.run(periodStart, periodEnd, DatevExportCadence.MONTHLY, logs)

        assertEquals(MonthlyCloseDto.ChecklistStatus.PASSED, result.status)
    }

    @Test
    fun `MONTHLY cadence PASSED when logs cover a wider range than the period`() {
        val logs =
            listOf(
                makeLog("KASSENBUCH", "2026-04-01T00:00:00", "2026-07-01T00:00:00"),
                makeLog("AP_LEDGER", "2026-04-01T00:00:00", "2026-07-01T00:00:00"),
            )
        val result = check.run(periodStart, periodEnd, DatevExportCadence.MONTHLY, logs)

        assertEquals(MonthlyCloseDto.ChecklistStatus.PASSED, result.status)
    }

    @Test
    fun `MONTHLY cadence FAILED when AP_LEDGER is missing`() {
        val logs =
            listOf(
                makeLog("KASSENBUCH", "2026-05-01T00:00:00", "2026-06-01T00:00:00"),
            )
        val result = check.run(periodStart, periodEnd, DatevExportCadence.MONTHLY, logs)

        assertEquals(MonthlyCloseDto.ChecklistStatus.FAILED, result.status)
    }

    @Test
    fun `MONTHLY cadence FAILED when KASSENBUCH is missing`() {
        val logs =
            listOf(
                makeLog("AP_LEDGER", "2026-05-01T00:00:00", "2026-06-01T00:00:00"),
            )
        val result = check.run(periodStart, periodEnd, DatevExportCadence.MONTHLY, logs)

        assertEquals(MonthlyCloseDto.ChecklistStatus.FAILED, result.status)
        assertEquals(MonthlyCloseDto.SectionId.DATEV_EXPORT, result.jumpTo)
        // admin16 NOTE-1: the client renders the line from code + args, not the English detail.
        assertEquals(MonthlyCloseDetailCodes.DATEV_MISSING_PERIOD, result.detailCode)
        assertEquals(listOf("KASSENBUCH"), result.detailArgs)
    }

    @Test
    fun `MONTHLY cadence FAILED when log period does not fully cover (end too early)`() {
        val logs =
            listOf(
                makeLog("KASSENBUCH", "2026-05-01T00:00:00", "2026-05-20T00:00:00"), // short
                makeLog("AP_LEDGER", "2026-05-01T00:00:00", "2026-06-01T00:00:00"),
            )
        val result = check.run(periodStart, periodEnd, DatevExportCadence.MONTHLY, logs)

        assertEquals(MonthlyCloseDto.ChecklistStatus.FAILED, result.status)
    }

    @Test
    fun `MONTHLY cadence FAILED when no logs present`() {
        val result = check.run(periodStart, periodEnd, DatevExportCadence.MONTHLY, exportLogs = emptyList())

        assertEquals(MonthlyCloseDto.ChecklistStatus.FAILED, result.status)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeLog(
        exportType: String,
        logPeriodStart: String,
        logPeriodEnd: String,
    ) = DatevExportLogDto(
        id = 1L,
        exportType = exportType,
        periodStart = logPeriodStart,
        periodEnd = logPeriodEnd,
        exportedAt = "2026-05-15T10:00:00",
        exportedByUserId = "00000000-0000-0000-0000-000000000001",
        fileHash = "abc123",
        fileName = "export.xml",
        rowCount = 10,
        entryCount = 10,
    )

    // E2E-2026-09-18 #10: with no AP invoices (or the AP auto-export OFF) there is nothing to export,
    // and the ad-hoc AP export answers 404 — the month could never close green.

    @Test
    fun `MONTHLY PASSED with only a KASSENBUCH export when AP_LEDGER is not required`() {
        val logs = listOf(makeLog("KASSENBUCH", "2026-05-01T00:00:00", "2026-06-01T00:00:00"))
        val result = check.run(periodStart, periodEnd, DatevExportCadence.MONTHLY, logs, apLedgerRequired = false)
        assertEquals(MonthlyCloseDto.ChecklistStatus.PASSED, result.status)
    }

    @Test
    fun `MONTHLY still FAILS without AP_LEDGER when it is required`() {
        val logs = listOf(makeLog("KASSENBUCH", "2026-05-01T00:00:00", "2026-06-01T00:00:00"))
        val result = check.run(periodStart, periodEnd, DatevExportCadence.MONTHLY, logs, apLedgerRequired = true)
        assertEquals(MonthlyCloseDto.ChecklistStatus.FAILED, result.status)
    }

    @Test
    fun `WEEKLY PASSED with KASSENBUCH weeks only when AP_LEDGER is not required`() {
        val logs = listOf(makeLog("KASSENBUCH", "2026-04-27T00:00:00", "2026-06-08T00:00:00"))
        val result = check.run(periodStart, periodEnd, DatevExportCadence.WEEKLY, logs, apLedgerRequired = false)
        assertEquals(MonthlyCloseDto.ChecklistStatus.PASSED, result.status)
    }

    @Test
    fun `WEEKLY failure carries the week count and the required types as structured args`() {
        val result = check.run(periodStart, periodEnd, DatevExportCadence.WEEKLY, exportLogs = emptyList())
        assertEquals(MonthlyCloseDto.ChecklistStatus.FAILED, result.status)
        assertEquals(MonthlyCloseDetailCodes.DATEV_MISSING_WEEKS, result.detailCode)
        // May 2026 touches five ISO weeks (27 Apr … 25 May).
        assertEquals(listOf("5", "KASSENBUCH", "AP_LEDGER"), result.detailArgs)
    }

    @Test
    fun `a passed check carries no detail code`() {
        val logs =
            listOf(
                makeLog("KASSENBUCH", "2026-05-01T00:00:00", "2026-06-01T00:00:00"),
                makeLog("AP_LEDGER", "2026-05-01T00:00:00", "2026-06-01T00:00:00"),
            )
        val result = check.run(periodStart, periodEnd, DatevExportCadence.MONTHLY, logs)
        assertEquals(null, result.detailCode)
        assertEquals(emptyList(), result.detailArgs)
    }
}
