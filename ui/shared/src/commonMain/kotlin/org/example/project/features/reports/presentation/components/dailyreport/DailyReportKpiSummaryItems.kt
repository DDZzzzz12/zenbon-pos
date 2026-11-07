package org.example.project.features.reports.presentation.components.dailyreport

import org.example.project.ui.components.KpiSummaryItem

/**
 * The revenue and tips items of the collapsed phone KPI line. Until a report has loaded the state
 * still holds its "0,00 €" defaults, which nobody computed, so both values are `null` (rendered as
 * the placeholder by `kpiSummaryText`, never as 0). A report counts as loaded once
 * [averageTipPerBill] is non-empty, which the state guarantees only after the first response.
 */
fun dailyReportKpiSummaryItems(
    averageTipPerBill: String,
    revenue: String,
    tips: String,
    revenueLabel: String,
    tipsLabel: String,
): List<KpiSummaryItem> {
    val loaded = averageTipPerBill.isNotEmpty()
    return listOf(
        KpiSummaryItem(revenueLabel, revenue.takeIf { loaded }),
        KpiSummaryItem(tipsLabel, tips.takeIf { loaded }),
    )
}
