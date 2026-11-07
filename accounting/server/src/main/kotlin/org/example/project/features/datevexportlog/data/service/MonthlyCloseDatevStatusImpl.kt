package org.example.project.features.datevexportlog.data.service

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.features.datevexportlog.domain.service.DatevExportLogService
import org.example.project.features.kassenbuch.domain.service.DatevApExportService
import org.example.project.features.monthlyclose.domain.service.MonthlyCloseDatevStatus

/**
 * The accounting side of the month-close DATEV port: the DATEV export log and the AP export's own
 * pre-flight statistics.
 */
class MonthlyCloseDatevStatusImpl(
    private val exportLogService: DatevExportLogService,
    private val apExportService: DatevApExportService,
) : MonthlyCloseDatevStatus {
    // findOverlapping's sourceFilter defaults to AUTO.
    override suspend fun autoExportLogs(
        periodStart: LocalDateTime,
        periodEnd: LocalDateTime,
    ): List<DatevExportLogDto> = exportLogService.findOverlapping(periodStart, periodEnd, null)

    override suspend fun allExportLogs(
        periodStart: LocalDateTime,
        periodEnd: LocalDateTime,
    ): List<DatevExportLogDto> = exportLogService.findOverlapping(periodStart, periodEnd, null, sourceFilter = null)

    override suspend fun apInvoiceCount(
        startDate: String,
        endDate: String,
    ): Int = apExportService.getApExportStatistics(startDate, endDate).totalInvoices
}
