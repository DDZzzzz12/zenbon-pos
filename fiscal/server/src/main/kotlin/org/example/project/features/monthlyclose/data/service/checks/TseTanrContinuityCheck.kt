package org.example.project.features.monthlyclose.data.service.checks

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDetailCodes
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.features.tse.findings.TseFindingService

/**
 * WARNS when the month holds TSE transaction numbers the POS has no ordinary record of and that
 * nobody has explained (design 2026-09-22 D3; AEAO § 146a Nr. 1.15.1, Swissbit AGD §5.2).
 * Advisory, like no_possible_double_sign: a gap is explainable, not in itself wrong
 * (TR-03153-1 §9.4). Requires an ambient transaction.
 */
class TseTanrContinuityCheck(
    private val findings: TseFindingService,
) {
    fun run(
        startLdt: LocalDateTime,
        endLdt: LocalDateTime,
    ): MonthlyCloseDto.ChecklistItemDto {
        val unexplained = findings.unexplainedCount(startLdt, endLdt)
        return MonthlyCloseDto.ChecklistItemDto(
            id = "tse_tanr_continuity",
            status = if (unexplained > 0) MonthlyCloseDto.ChecklistStatus.WARNING else MonthlyCloseDto.ChecklistStatus.PASSED,
            detail =
                if (unexplained > 0) {
                    "$unexplained TSE transaction number range(s) have no POS record and no explanation; explain them under TSE-Befunde"
                } else {
                    ""
                },
            affectedCount = unexplained,
            jumpTo = MonthlyCloseDto.SectionId.DISCREPANCIES,
            detailCode = if (unexplained > 0) MonthlyCloseDetailCodes.UNEXPLAINED_TSE_RANGES else null,
            detailArgs = if (unexplained > 0) listOf(unexplained.toString()) else emptyList(),
        )
    }
}
