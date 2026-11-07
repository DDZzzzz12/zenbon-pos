package org.example.project.features.monthlyclose.data.service.checks

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDetailCodes
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.features.bill.domain.repository.BillRepository

/**
 * Checks that no bills remain open (unassigned to a Z-Bon) within the close period.
 *
 * A bill is considered "open" when its zBonId is null and it has not been cancelled
 * (deletingReason == null). Open bills at period-end indicate the Z-Bon run has not
 * been completed, meaning financial totals are incomplete.
 *
 * Result: FAILED if any open bills are found, PASSED otherwise.
 * affectedCount = number of open bills in the window.
 */
class OpenBillsCheck(
    private val billRepository: BillRepository,
) {
    suspend fun run(
        startLdt: LocalDateTime,
        endLdt: LocalDateTime,
    ): MonthlyCloseDto.ChecklistItemDto {
        val count = billRepository.countOpenInWindow(startLdt, endLdt)

        val status =
            if (count > 0) {
                MonthlyCloseDto.ChecklistStatus.FAILED
            } else {
                MonthlyCloseDto.ChecklistStatus.PASSED
            }

        val detail = if (count > 0) "$count open bill(s) not yet assigned to a Z-Bon" else ""

        return MonthlyCloseDto.ChecklistItemDto(
            id = "no_open_bills",
            status = status,
            detail = detail,
            affectedCount = count,
            jumpTo = MonthlyCloseDto.SectionId.DISCREPANCIES,
            detailCode = if (count > 0) MonthlyCloseDetailCodes.OPEN_BILLS else null,
            detailArgs = if (count > 0) listOf(count.toString()) else emptyList(),
        )
    }
}
