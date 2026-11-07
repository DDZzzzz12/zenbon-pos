package org.example.project.features.monthlyclose.data.service.checks

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDetailCodes
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.features.monthlyclose.data.repository.SimulatedSignatureRepository

/**
 * FAILS when a Vorgang in the period is marked SIGNED but carries a simulated signature — the state
 * a NORMAL-configured shop without reachable TSE hardware produced before 2026-09-19. Such a Beleg
 * has no valid TSE signature (§146a AO) even though its status says it has; `all_tse_signed`
 * checks status only and passes it.
 */
class SimulatedSignatureCheck(
    private val repository: SimulatedSignatureRepository,
) {
    fun run(
        startLdt: LocalDateTime,
        endLdt: LocalDateTime,
    ): MonthlyCloseDto.ChecklistItemDto {
        val count = repository.countSimulatedButSigned(startLdt, endLdt)
        return MonthlyCloseDto.ChecklistItemDto(
            id = "no_simulated_signatures",
            status = if (count > 0) MonthlyCloseDto.ChecklistStatus.FAILED else MonthlyCloseDto.ChecklistStatus.PASSED,
            detail =
                if (count > 0) {
                    "$count Vorgang/Vorgänge marked SIGNED carry a simulated TSE signature (serial SIM-TSE-001)"
                } else {
                    ""
                },
            affectedCount = count,
            jumpTo = MonthlyCloseDto.SectionId.DISCREPANCIES,
            detailCode = if (count > 0) MonthlyCloseDetailCodes.SIMULATED_SIGNATURES else null,
            detailArgs = if (count > 0) listOf(count.toString()) else emptyList(),
        )
    }
}
