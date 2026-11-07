package org.example.project.features.monthlyclose.checks

import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDetailCodes
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.core.domain.dto.statistics.VatSummaryDto
import org.example.project.features.monthlyclose.data.service.checks.VatConsistencyCheck
import org.example.project.features.monthlyclose.domain.model.MonthlyCloseFinancialOverview
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class VatConsistencyCheckTest {
    private val check = VatConsistencyCheck()

    @Test
    fun `PASSED when bill vat total matches zbon vat total within tolerance`() {
        val financial = buildFinancial(vatAmounts = listOf("100.00", "50.00")) // sum = 150.00
        val rollup = buildRollup(vatTotal = "150.00")

        val result = check.run(financial, rollup)

        assertEquals(MonthlyCloseDto.ChecklistStatus.PASSED, result.status)
        assertEquals("vat_consistent", result.id)
        assertEquals(0, result.affectedCount)
        assertEquals(MonthlyCloseDetailCodes.VAT_TOTAL_ONLY, result.detailCode)
    }

    @Test
    fun `PASSED when delta is exactly at tolerance boundary (0,01)`() {
        val financial = buildFinancial(vatAmounts = listOf("100.00")) // sum = 100.00
        val rollup = buildRollup(vatTotal = "100.01") // delta = 0.01 — boundary is inclusive

        val result = check.run(financial, rollup)

        assertEquals(MonthlyCloseDto.ChecklistStatus.PASSED, result.status)
    }

    @Test
    fun `FAILED when bill vat total differs from zbon total by more than 0,01`() {
        val financial = buildFinancial(vatAmounts = listOf("100.00")) // sum = 100.00
        val rollup = buildRollup(vatTotal = "100.02") // delta = 0.02 — exceeds tolerance

        val result = check.run(financial, rollup)

        assertEquals(MonthlyCloseDto.ChecklistStatus.FAILED, result.status)
        assertEquals(MonthlyCloseDto.SectionId.VAT, result.jumpTo)
        assertEquals(1, result.affectedCount)
        assertEquals(MonthlyCloseDetailCodes.VAT_DELTA, result.detailCode)
        assertEquals(listOf("0.02", "100.00", "100.02"), result.detailArgs)
    }

    @Test
    fun `FAILED when zbon vat total is significantly higher than bill total`() {
        val financial = buildFinancial(vatAmounts = listOf("50.00"))
        val rollup = buildRollup(vatTotal = "200.00")

        val result = check.run(financial, rollup)

        assertEquals(MonthlyCloseDto.ChecklistStatus.FAILED, result.status)
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun buildFinancial(vatAmounts: List<String>): MonthlyCloseFinancialOverview {
        val vatBreakdown =
            vatAmounts
                .mapIndexed { idx, amount ->
                    "VAT_$idx" to
                        VatSummaryDto(
                            vatRate = "${idx * 7}",
                            netAmount = "0.00",
                            vatAmount = amount,
                            totalAmount = amount,
                            transactionCount = 1,
                        )
                }.toMap()

        return MonthlyCloseFinancialOverview(
            currency = "EUR",
            grossRevenue = "1000.00",
            netRevenue = "850.00",
            totalRefunds = "0.00",
            totalTips = "0.00",
            vatBreakdown = vatBreakdown,
            paymentMethodBreakdown = emptyMap(),
            dailySummary = emptyMap(),
        )
    }

    private fun buildRollup(vatTotal: String): MonthlyCloseDto.ZBonRollupDto {
        val totalsRow =
            MonthlyCloseDto.ZBonRollupRowDto(
                zBonId = 0L,
                zBonNumber = "TOTAL",
                startTime = "2026-05-01T00:00:00Z",
                endTime = "2026-06-01T00:00:00Z",
                grossRevenue = "1000.00",
                netRevenue = "850.00",
                vatTotal = vatTotal,
                tips = "0.00",
                refunds = "0.00",
                cancellations = "0.00",
                paymentMethodTotals = emptyList(),
            )
        return MonthlyCloseDto.ZBonRollupDto(
            rows = emptyList(),
            totals = totalsRow,
            chainGaps = emptyList(),
        )
    }
}
