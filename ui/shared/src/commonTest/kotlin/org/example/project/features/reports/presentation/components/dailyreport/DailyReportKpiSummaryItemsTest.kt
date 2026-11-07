package org.example.project.features.reports.presentation.components.dailyreport

import org.example.project.ui.components.kpiSummaryText
import kotlin.test.Test
import kotlin.test.assertEquals

class DailyReportKpiSummaryItemsTest {
    private fun items(averageTipPerBill: String) =
        dailyReportKpiSummaryItems(
            averageTipPerBill = averageTipPerBill,
            revenue = "0,00 €",
            tips = "0,00 €",
            revenueLabel = "Umsatz",
            tipsLabel = "Trinkgeld",
        )

    @Test
    fun beforeAReportHasLoadedBothValuesAreMissingNotZero() {
        val result = items(averageTipPerBill = "")
        assertEquals(listOf(null, null), result.map { it.value })
        assertEquals(listOf("Umsatz", "Trinkgeld"), result.map { it.label })
        assertEquals(false, kpiSummaryText(result).contains("0,00"))
    }

    @Test
    fun onceLoadedBothFiguresAreShown() {
        val result = items(averageTipPerBill = "1,20 €")
        assertEquals(listOf("0,00 €", "0,00 €"), result.map { it.value })
    }
}
