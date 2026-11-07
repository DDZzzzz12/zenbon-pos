package org.example.project.features.monthlyclose.data.service.checks

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDetailCodes
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.features.kassenbuch.domain.repository.KassenbuchRepository

/**
 * Checks that all Kassenbuch (cash book) entries in the period have a linked document.
 *
 * German GoBD compliance requires that each cash movement is backed by a receipt or
 * supporting document. Entries with linkedDocumentId == null are undocumented.
 *
 * Note: the KassenbuchHeader model does not have a `noReceiptReason` field; the
 * sole criteria for "documented" is whether linkedDocumentId is non-null.
 *
 * Result: FAILED if any undocumented entries found, PASSED otherwise.
 * affectedCount = number of entries without a linked document.
 */
class CashMovementsDocumentedCheck(
    private val kassenbuchRepository: KassenbuchRepository,
) {
    suspend fun run(
        startLdt: LocalDateTime,
        endLdt: LocalDateTime,
    ): MonthlyCloseDto.ChecklistItemDto {
        val count = kassenbuchRepository.countUndocumentedInWindow(startLdt, endLdt)

        val status =
            if (count > 0) {
                MonthlyCloseDto.ChecklistStatus.FAILED
            } else {
                MonthlyCloseDto.ChecklistStatus.PASSED
            }

        val detail = if (count > 0) "$count cash movement(s) without a linked supporting document" else ""

        return MonthlyCloseDto.ChecklistItemDto(
            id = "all_cash_documented",
            status = status,
            detail = detail,
            affectedCount = count,
            jumpTo = MonthlyCloseDto.SectionId.KASSENBUCH_ROLLUP,
            detailCode = if (count > 0) MonthlyCloseDetailCodes.UNDOCUMENTED_CASH_MOVEMENTS else null,
            detailArgs = if (count > 0) listOf(count.toString()) else emptyList(),
        )
    }
}
