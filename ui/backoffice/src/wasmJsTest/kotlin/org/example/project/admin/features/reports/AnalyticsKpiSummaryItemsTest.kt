package org.example.project.admin.features.reports

import org.example.project.admin.features.reports.presentation.analyticsKpiSummaryItems
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.utils.EMPTY_VALUE
import org.example.project.ui.components.kpiSummaryText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The collapsed phone KPI line of Analysen holds period figures only (revenue, bills, average and,
 * when tables are open, the provisional open amount); comparison deltas stay in the expanded grid.
 * Before the first response there are no figures, and they read as the placeholder, never as 0.
 */
class AnalyticsKpiSummaryItemsTest {
    private val labels = listOf("Umsatz", "Rechnungen", "Ø Bon", "Offen")

    private fun items(overview: StatisticsDto.SalesOverviewResponseDto?) = analyticsKpiSummaryItems(overview, labels[0], labels[1], labels[2], labels[3])

    private fun overview(
        revenue: String = "16.90",
        bills: Int = 2,
        open: String = "0.00",
    ) = StatisticsDto.SalesOverviewResponseDto(
        period = "2026-10-21",
        totalRevenue = revenue,
        billedRevenue = revenue,
        canceledRevenue = "0.00",
        totalBillCount = bills,
        totalTips = "1.00",
        openItemAmount = open,
        currency = "EUR",
    )

    @Test
    fun withoutAResponseEveryFigureIsThePlaceholder() {
        val result = items(null)
        assertEquals(listOf("Umsatz", "Rechnungen", "Ø Bon"), result.map { it.label })
        assertEquals("Umsatz $EMPTY_VALUE · Rechnungen $EMPTY_VALUE · Ø Bon $EMPTY_VALUE", kpiSummaryText(result))
    }

    @Test
    fun revenueBillsAndAverageAreThePeriodFigures() {
        val result = items(overview())
        assertEquals(listOf("Umsatz", "Rechnungen", "Ø Bon"), result.map { it.label })
        assertEquals("2", result[1].value)
        assertTrue(result[0].value!!.contains("16,90"), "revenue formatted through the money kernel: ${result[0].value}")
        assertTrue(result[2].value!!.contains("8,45"), "average divided in Decimal: ${result[2].value}")
    }

    @Test
    fun theOpenAmountJoinsOnlyWhenTablesAreOpen() {
        val result = items(overview(open = "12.50"))
        assertEquals("Offen", result.last().label)
        assertTrue(result.last().value!!.contains("12,50"))
    }
}
