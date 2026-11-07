package org.example.project.features.monthlyclose.data.service.checks

import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDetailCodes
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto

/**
 * Checks that no bills exist outside Z-Bon coverage windows.
 *
 * Uses the chain gaps already computed in the ZBonRollupDto. For each gap,
 * if `hasBillsInGap == true` then at least one bill was recorded in that
 * uncovered window — this is a compliance problem (DATEV / DSFinV-K).
 *
 * affectedCount = number of gaps that contain bills.
 * Result: FAILED if affectedCount > 0, PASSED otherwise.
 */
class BillsOutsideZBonCheck {
    fun run(zBonRollup: MonthlyCloseDto.ZBonRollupDto): MonthlyCloseDto.ChecklistItemDto {
        val affectedGaps = zBonRollup.chainGaps.count { it.hasBillsInGap }

        val status =
            if (affectedGaps > 0) {
                MonthlyCloseDto.ChecklistStatus.FAILED
            } else {
                MonthlyCloseDto.ChecklistStatus.PASSED
            }

        val detail = if (affectedGaps > 0) "$affectedGaps gap(s) contain bills outside Z-Bon coverage" else ""

        return MonthlyCloseDto.ChecklistItemDto(
            id = "no_bills_outside_zbon",
            status = status,
            detail = detail,
            affectedCount = affectedGaps,
            jumpTo = MonthlyCloseDto.SectionId.DISCREPANCIES,
            detailCode = if (affectedGaps > 0) MonthlyCloseDetailCodes.BILLS_OUTSIDE_ZBON else null,
            detailArgs = if (affectedGaps > 0) listOf(affectedGaps.toString()) else emptyList(),
        )
    }
}
