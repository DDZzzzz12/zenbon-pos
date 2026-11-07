package org.example.project.features.monthlyclose.data.service.checks

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDetailCodes
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto

/**
 * Checks that Z-Bon records do not overlap in time.
 *
 * Walks the Z-Bon rollup rows in order. If any row's startTime is strictly less than
 * the previous row's endTime, that is an overlap — indicating a Z-Bon period integrity
 * violation. Closed-day gaps between Z-Bons are NOT flagged here; only overlaps are.
 *
 * Result: FAILED if any overlap pair found, PASSED otherwise.
 * affectedCount = number of overlap pairs detected.
 */
class ChainIntegrityCheck {
    fun run(zBonRollup: MonthlyCloseDto.ZBonRollupDto): MonthlyCloseDto.ChecklistItemDto {
        val rows = zBonRollup.rows
        var overlapCount = 0

        for (i in 1 until rows.size) {
            val prev = rows[i - 1]
            val curr = rows[i]
            val prevEnd = LocalDateTime.parse(prev.endTime)
            val currStart = LocalDateTime.parse(curr.startTime)
            if (currStart < prevEnd) {
                overlapCount++
            }
        }

        val status =
            if (overlapCount > 0) {
                MonthlyCloseDto.ChecklistStatus.FAILED
            } else {
                MonthlyCloseDto.ChecklistStatus.PASSED
            }

        val detail = if (overlapCount > 0) "$overlapCount overlap(s) detected" else ""

        return MonthlyCloseDto.ChecklistItemDto(
            id = "zbon_chain_integrity",
            status = status,
            detail = detail,
            affectedCount = overlapCount,
            jumpTo = MonthlyCloseDto.SectionId.ZBON_ROLLUP,
            detailCode = if (overlapCount > 0) MonthlyCloseDetailCodes.ZBON_OVERLAPS else null,
            detailArgs = if (overlapCount > 0) listOf(overlapCount.toString()) else emptyList(),
        )
    }
}
