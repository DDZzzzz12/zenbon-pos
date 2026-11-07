package org.example.project.features.tse.findings

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.example.project.core.domain.dto.tse.TseExplanationDto
import org.example.project.core.domain.enums.tse.TseExplanationSource
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery

/** TANR findings and their explanations (design 2026-09-22 §4.7). */
class TseFindingService(
    private val tanrs: TseTanrRepository,
    private val explanations: TseExplanationRepository,
) {
    /** Findings for [from]..[to] (inclusive dates); both null means the whole history. */
    suspend fun findings(
        from: LocalDate?,
        to: LocalDate?,
    ): TseExplanationDto.FindingsResponseDto =
        dbQuery {
            val start = from?.let { LocalDateTime(it, LocalTime(0, 0)) } ?: LocalDateTime(2000, 1, 1, 0, 0)
            val end =
                to?.let { LocalDateTime(LocalDate.fromEpochDays(it.toEpochDays() + 1), LocalTime(0, 0)) }
                    ?: LocalDateTime(2100, 1, 1, 0, 0)
            val current = explanations.current()
            val findings =
                TanrContinuityAnalyzer.analyze(tanrs.rows(), start, end).map { f ->
                    TseExplanationDto.FindingDto(
                        tseSerialHex = f.serialHex,
                        tanrFrom = f.tanrFrom,
                        tanrTo = f.tanrTo,
                        kind = f.kind,
                        explanationId = current.firstOrNull { it.covers(f) }?.id,
                    )
                }
            TseExplanationDto.FindingsResponseDto(findings, current, history = explanations.all())
        }

    suspend fun explain(
        request: TseExplanationDto.CreateRequestDto,
        employeeId: String,
    ): TseExplanationDto.ExplanationDto {
        request.validationError()?.let { throw refused(it) }
        return dbQuery {
            request.supersedesId?.let { requireCorrectable(it, request) }
            explanations.insert(
                serialHex = request.tseSerialHex.uppercase(),
                tanrFrom = request.tanrFrom,
                tanrTo = request.tanrTo,
                category = request.category,
                text = request.text.trim(),
                source = TseExplanationSource.OPERATOR,
                employeeId = employeeId,
                supersedesId = request.supersedesId,
            )
        }
    }

    suspend fun explanation(id: Long): TseExplanationDto.ExplanationDto? = dbQuery { explanations.byId(id) }

    /**
     * A correction replaces an explanation of the SAME range on the SAME device, and only one that
     * nothing has replaced yet.
     *
     * There is no foreign key on `supersedes_id`, and [TseExplanationRepository.current] drops
     * every id any entry names — so an unchecked id would silently remove an unrelated, still-valid
     * explanation from the register and from the CSV export, and flip the finding it covered back
     * to "not explained". The row would survive, but the register an auditor reads would be wrong,
     * which is the one thing it may not be. Two corrections naming the same entry would likewise
     * both stay current for one range, after which `covers` picks one arbitrarily.
     *
     * Requires an ambient transaction.
     */
    private fun requireCorrectable(
        supersedesId: Long,
        request: TseExplanationDto.CreateRequestDto,
    ) {
        val target =
            explanations.byId(supersedesId)
                ?: throw refused("supersedesId $supersedesId names no explanation")
        if (explanations.isSuperseded(supersedesId)) {
            throw refused("explanation $supersedesId was already superseded by a later correction; correct that one instead")
        }
        if (!target.tseSerialHex.equals(request.tseSerialHex, ignoreCase = true)) {
            throw refused(
                "explanation $supersedesId belongs to device ${target.tseSerialHex}, " +
                    "not ${request.tseSerialHex.uppercase()}; a correction stays on the same device",
            )
        }
        if (target.tanrFrom != request.tanrFrom || target.tanrTo != request.tanrTo) {
            throw refused(
                "explanation $supersedesId covers TANR ${target.tanrFrom}-${target.tanrTo}, " +
                    "not ${request.tanrFrom}-${request.tanrTo}; a correction replaces the same range",
            )
        }
    }

    private fun refused(message: String) = PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest, message)

    /** Unexplained findings in the window; the month-end check's measure. Requires an ambient transaction. */
    fun unexplainedCount(
        start: LocalDateTime,
        end: LocalDateTime,
    ): Int {
        val current = explanations.current()
        return TanrContinuityAnalyzer.analyze(tanrs.rows(), start, end).count { f -> current.none { it.covers(f) } }
    }

    private fun TseExplanationDto.ExplanationDto.covers(f: TanrFinding): Boolean =
        tseSerialHex.equals(f.serialHex, ignoreCase = true) && tanrFrom <= f.tanrFrom && tanrTo >= f.tanrTo
}
