package org.example.project.admin.features.reports.presentation

import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.utils.averageMoneyPerCount
import org.example.project.money.Decimal
import org.example.project.ui.components.KpiSummaryItem
import org.example.project.ui.components.formatMoneyForDisplay

/**
 * The figures of the collapsed phone KPI line on Analysen: the period's revenue, bill count and
 * average bill, plus the provisional open amount when tables are open (the same rule as its card).
 * Period figures only: the comparison deltas and the tips stay in the expanded grid. Without a
 * response ([overview] null) every value is null, rendered as the placeholder, never as 0.
 */
@Suppress("LongParameterList") // the four localized labels, resolved by the composable caller
fun analyticsKpiSummaryItems(
    overview: StatisticsDto.SalesOverviewResponseDto?,
    revenueLabel: String,
    billsLabel: String,
    averageLabel: String,
    openLabel: String,
): List<KpiSummaryItem> {
    val items =
        listOf(
            KpiSummaryItem(revenueLabel, overview?.let { formatMoneyForDisplay(it.totalRevenue, it.currency) }),
            // A count, not money — never routed through the money formatter.
            KpiSummaryItem(billsLabel, overview?.totalBillCount?.toString()),
            KpiSummaryItem(averageLabel, overview?.let { averageMoneyPerCount(it.totalRevenue, it.totalBillCount, it.currency) }),
        )
    if (overview == null || !hasOpenAmount(overview)) return items
    return items + KpiSummaryItem(openLabel, formatMoneyForDisplay(overview.openItemAmount, overview.currency))
}

/** Whether the period holds money on tables that has not been billed (the open card's own test). */
internal fun hasOpenAmount(overview: StatisticsDto.SalesOverviewResponseDto): Boolean =
    (Decimal.parseOrNull(overview.openItemAmount) ?: Decimal.ZERO) > Decimal.ZERO
