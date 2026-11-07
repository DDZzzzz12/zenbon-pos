package org.example.project.admin.features.reports

import org.example.project.admin.features.reports.presentation.components.datevAmountForDisplay
import org.example.project.admin.features.reports.presentation.components.displayValue
import org.example.project.admin.features.reports.presentation.components.revenueReconciliationArgs
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * admin16 Z8d: the Monatsabschluss tiles and the Z-Bon / Kassenbuch roll-ups printed the server's
 * canonical strings ("258.52", "5524.72") with no € sign, beside a screen that writes "258,52 €".
 */
class MonthlyCloseMoneyDisplayTest {
    private fun kpi(
        key: String,
        value: String,
    ) = MonthlyCloseDto.KpiMetricDto(key = key, value = value, dailySeries = emptyList(), previousValue = null, deltaPercent = null)

    @Test
    fun moneyKpisAreFormattedGermanStyleWithTheCurrency() {
        assertEquals("258,52 €", kpi("grossRevenue", "258.52").displayValue("EUR"))
        assertEquals("5.524,72 €", kpi("kassenbuchClosing", "5524.72").displayValue("EUR"))
    }

    @Test
    fun theBillCountStaysACount() {
        assertEquals("18", kpi("billCount", "18").displayValue("EUR"))
    }

    @Test
    fun aDatevAmountKeepsItsSignAndGainsTheCurrency() {
        assertEquals("+84,40 €", datevAmountForDisplay("+84,40", "EUR"))
        assertEquals("-12,00 €", datevAmountForDisplay("-12,00", "EUR"))
    }

    @Test
    fun anUnexpectedDatevStringIsShownUnchanged() {
        assertEquals("n/a", datevAmountForDisplay("n/a", "EUR"))
    }

    /** admin18 NOTE-21: a pre-1.1.0 closing's stored Umsatz holds its promotions; the line says by how much. */
    @Test
    fun aStoredUmsatzAboveTheBillsGetsAReconciliationLine() {
        val legacy =
            zBonRow(zBonNumber = "146").copy(grossRevenue = "6420.30", revenueFromBills = "6300.70", storedRevenueDifference = "119.60")
        val current = zBonRow(zBonNumber = "154").copy(grossRevenue = "273.22", revenueFromBills = "273.22")
        val totals =
            zBonRow(zBonNumber = "TOTAL").copy(
                grossRevenue = "6693.52",
                revenueFromBills = "6573.92",
                storedRevenueDifference = "119.60",
            )
        val args = MonthlyCloseDto.ZBonRollupDto(listOf(legacy, current), totals, emptyList()).revenueReconciliationArgs("EUR")
        assertEquals(listOf("6.693,52 €", "6.573,92 €", "119,60 €", "146"), args)
    }

    @Test
    fun closingsThatAgreeWithTheirBillsGetNoLine() {
        val current = zBonRow(zBonNumber = "154").copy(grossRevenue = "273.22", revenueFromBills = "273.22")
        val totals = zBonRow(zBonNumber = "TOTAL").copy(grossRevenue = "273.22", revenueFromBills = "273.22", storedRevenueDifference = "0.00")
        assertNull(MonthlyCloseDto.ZBonRollupDto(listOf(current), totals, emptyList()).revenueReconciliationArgs("EUR"))
    }
}
