package org.example.project.admin.features.reports

import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto

/**
 * A minimal but structurally complete monthly-close response. Every field the UI reads is present
 * so a section test never has to build the whole DTO again.
 */
internal fun monthlyClose(
    periodStart: String = "2026-07-01T00:00",
    periodEnd: String = "2026-08-01T00:00",
    kpis: List<MonthlyCloseDto.KpiMetricDto> = listOf(grossRevenueKpi()),
    checklist: List<MonthlyCloseDto.ChecklistItemDto> = emptyList(),
    zBonRows: List<MonthlyCloseDto.ZBonRollupRowDto> = emptyList(),
    chainGaps: List<MonthlyCloseDto.ZBonChainGapDto> = emptyList(),
    kassenbuchGroups: List<MonthlyCloseDto.KassenbuchRollupGroupDto> = emptyList(),
    log: List<DatevExportLogDto> = emptyList(),
    badgeStatus: MonthlyCloseDto.ExportBadgeStatus = MonthlyCloseDto.ExportBadgeStatus.NOT_EXPORTED,
) = MonthlyCloseDto.MonthlyCloseResponseDto(
    periodStart = periodStart,
    periodEnd = periodEnd,
    kpis = kpis,
    previousPeriodStart = null,
    previousPeriodEnd = null,
    vatBreakdown = emptyMap(),
    paymentBreakdown = emptyMap(),
    zBonRollup =
        MonthlyCloseDto.ZBonRollupDto(
            rows = zBonRows,
            totals = zBonRow(zBonNumber = "TOTAL"),
            chainGaps = chainGaps,
        ),
    kassenbuchRollup =
        MonthlyCloseDto.KassenbuchRollupDto(
            openingBalance = "100.00",
            closingBalance = "250.00",
            groups = kassenbuchGroups,
        ),
    checklist = checklist,
    exportStatus =
        MonthlyCloseDto.ExportStatusDto(
            badgeStatus = badgeStatus,
            lastExportedAt = null,
            log = log,
        ),
)

internal fun grossRevenueKpi(
    value: String = "1000.00",
    dailySeries: List<String> = listOf("100.00", "200.00", "700.00"),
    deltaPercent: String? = "12.5",
) = MonthlyCloseDto.KpiMetricDto(
    key = "grossRevenue",
    value = value,
    dailySeries = dailySeries,
    previousValue = "888.00",
    deltaPercent = deltaPercent,
)

internal fun checklistItem(
    id: String = "no_open_bills",
    status: MonthlyCloseDto.ChecklistStatus = MonthlyCloseDto.ChecklistStatus.PASSED,
    affectedCount: Int = 0,
    detail: String = "",
) = MonthlyCloseDto.ChecklistItemDto(
    id = id,
    status = status,
    detail = detail,
    affectedCount = affectedCount,
    jumpTo = MonthlyCloseDto.SectionId.OVERVIEW,
)

internal fun zBonRow(
    zBonNumber: String = "Z-1",
    startTime: String = "2026-07-01T08:00",
    endTime: String = "2026-07-01T23:00",
) = MonthlyCloseDto.ZBonRollupRowDto(
    zBonId = 1L,
    zBonNumber = zBonNumber,
    startTime = startTime,
    endTime = endTime,
    grossRevenue = "1000.00",
    netRevenue = "840.34",
    vatTotal = "159.66",
    tips = "10.00",
    refunds = "0.00",
    cancellations = "0.00",
    paymentMethodTotals = emptyList(),
)
