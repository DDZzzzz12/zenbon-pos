package org.example.project.features.monthlyclose.data.service.checks

import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDetailCodes
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.features.monthlyclose.domain.model.MonthlyCloseFinancialOverview
import java.math.BigDecimal

/**
 * Checks that VAT totals in financial overview match the Z-Bon rollup totals.
 *
 * Per-rate cross-check is not performed here because [MonthlyCloseDto.ZBonRollupRowDto]
 * does not carry a per-rate VAT breakdown — only a `vatTotal` aggregate.
 * This is a known limitation: to enable per-rate comparison the rollup DTO
 * would need to be extended with a vatByRate map.
 *
 * Current logic (total-only comparison):
 *   billsVatSum  = Σ financial.vatBreakdown[*].vatAmount
 *   zbonVatTotal = zBonRollup.totals.vatTotal
 *   FAILED if |billsVatSum - zbonVatTotal| > 0.01
 *   PASSED otherwise
 *   DONE_WITH_CONCERNS not used here; instead we note the limitation in detail.
 */
class VatConsistencyCheck {
    companion object {
        private val TOLERANCE: BigDecimal = BigDecimal("0.01")
    }

    fun run(
        financial: MonthlyCloseFinancialOverview,
        zBonRollup: MonthlyCloseDto.ZBonRollupDto,
    ): MonthlyCloseDto.ChecklistItemDto {
        val billsVatSum =
            financial.vatBreakdown.values
                .sumOf { it.vatAmount.toBigDecimalOrNull() ?: BigDecimal.ZERO }

        val zbonVatTotal = zBonRollup.totals.vatTotal.toBigDecimalOrNull() ?: BigDecimal.ZERO

        val delta = (billsVatSum - zbonVatTotal).abs()
        val passed = delta <= TOLERANCE

        val status =
            if (passed) {
                MonthlyCloseDto.ChecklistStatus.PASSED
            } else {
                MonthlyCloseDto.ChecklistStatus.FAILED
            }

        val detail =
            if (passed) {
                "Note: per-rate cross-check skipped; rollup DTO carries only vatTotal aggregate"
            } else {
                "VAT delta = ${delta.toPlainString()} (bills: ${billsVatSum.toPlainString()}, " +
                    "ZBon: ${zbonVatTotal.toPlainString()}). " +
                    "Note: per-rate cross-check skipped; rollup DTO carries only vatTotal aggregate"
            }

        return MonthlyCloseDto.ChecklistItemDto(
            id = "vat_consistent",
            status = status,
            detail = detail,
            affectedCount = if (passed) 0 else 1,
            jumpTo = MonthlyCloseDto.SectionId.VAT,
            detailCode = if (passed) MonthlyCloseDetailCodes.VAT_TOTAL_ONLY else MonthlyCloseDetailCodes.VAT_DELTA,
            detailArgs =
                if (passed) {
                    emptyList()
                } else {
                    listOf(delta.toPlainString(), billsVatSum.toPlainString(), zbonVatTotal.toPlainString())
                },
        )
    }
}
