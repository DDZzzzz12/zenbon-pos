package org.example.project.features.monthlyclose.data.service.checks

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDetailCodes
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.features.bill.domain.repository.BillRepository

/**
 * Checks that all bills in the close period have a valid TSE signature.
 *
 * Bills with tseSigningStatus in {PENDING, PROCESSING, QUEUED, FAILED} are considered
 * unsigned — i.e., they have not been successfully signed by the TSE device.
 * CANCELLED and DISABLED are excluded from this check (not compliance-critical for
 * voided/training-mode bills).
 *
 * This check reads TSE state only via BillRepository. The TSE module itself is
 * NOT modified — this is a read-only query on the bill table's tseSigningStatus column.
 *
 * Result: FAILED if any unsigned bills found, PASSED otherwise.
 * affectedCount = number of bills with non-terminal signing status.
 */
class UnsignedTseCheck(
    private val billRepository: BillRepository,
) {
    suspend fun run(
        startLdt: LocalDateTime,
        endLdt: LocalDateTime,
    ): MonthlyCloseDto.ChecklistItemDto {
        val count = billRepository.countUnsignedTseInWindow(startLdt, endLdt)

        val status =
            if (count > 0) {
                MonthlyCloseDto.ChecklistStatus.FAILED
            } else {
                MonthlyCloseDto.ChecklistStatus.PASSED
            }

        val detail = if (count > 0) "$count bill(s) missing a valid TSE signature (KassenSichV)" else ""

        return MonthlyCloseDto.ChecklistItemDto(
            id = "all_tse_signed",
            status = status,
            detail = detail,
            affectedCount = count,
            jumpTo = MonthlyCloseDto.SectionId.DISCREPANCIES,
            detailCode = if (count > 0) MonthlyCloseDetailCodes.UNSIGNED_BILLS else null,
            detailArgs = if (count > 0) listOf(count.toString()) else emptyList(),
        )
    }
}
