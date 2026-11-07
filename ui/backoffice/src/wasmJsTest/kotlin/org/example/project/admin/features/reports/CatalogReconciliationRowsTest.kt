package org.example.project.admin.features.reports

import org.example.project.admin.features.reports.presentation.components.CatalogReconciliationLabels
import org.example.project.admin.features.reports.presentation.components.catalogReconciliationRows
import org.example.project.core.domain.dto.statistics.StatisticsDto
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * admin17 NOTE-11: the Katalog's category column must add up to the Umsatz tile. The rows under it
 * subtract the bill-wide promotions and add the owner tip, and close on the reconciled Umsatz.
 */
class CatalogReconciliationRowsTest {
    private val labels = CatalogReconciliationLabels(billPromotions = "Rabatte (Beleg)", ownerTips = "Inhabertrinkgeld", total = "Umsatz")

    private fun categories(
        billWide: String,
        ownerTips: String,
        total: String,
    ) = StatisticsDto.CategoryPerformanceResponseDto(
        period = "",
        categoryMetrics = emptyList(),
        mostProfitableCategory = "",
        fastestGrowingCategory = "",
        categoryRevenueDistribution = emptyMap(),
        billWidePromotions = billWide,
        ownerTips = ownerTips,
        reconciledRevenue = total,
    )

    @Test
    fun bill_promotions_are_negative_the_owner_tip_positive_and_the_total_emphasized() {
        val rows = catalogReconciliationRows(categories("12.5000", "4.0000", "258.5200"), "EUR", labels, columnCount = 3)

        assertEquals(
            listOf(
                listOf("Rabatte (Beleg)", "", "-12,50 €"),
                listOf("Inhabertrinkgeld", "", "4,00 €"),
                listOf("Umsatz", "", "258,52 €"),
            ),
            rows.map { it.cells },
        )
        assertEquals(listOf(false, false, true), rows.map { it.emphasized })
    }

    @Test
    fun zero_figures_get_no_row_and_the_growth_cell_stays_blank() {
        val rows = catalogReconciliationRows(categories("0.00", "0.0000", "17.25"), "EUR", labels, columnCount = 4)

        assertEquals(listOf(listOf("Umsatz", "", "17,25 €", "")), rows.map { it.cells })
    }
}
