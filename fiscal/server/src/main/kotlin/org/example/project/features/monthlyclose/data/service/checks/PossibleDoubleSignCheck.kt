/**
 * Monthly-close check surfacing Vorgänge whose TSE signature may have been produced twice.
 *
 * @since 1.0.0
 */
package org.example.project.features.monthlyclose.data.service.checks

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.audit.TransactionAuditDto
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDetailCodes
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.features.audit.domain.service.TransactionAuditService
import org.example.project.features.tse.data.service.TseSigningQueueServiceImpl

/**
 * M7: reports Vorgänge for which the TSE response was LOST after the request was sent.
 *
 * In that state the device may already hold a signature for the payload, yet nothing was persisted
 * locally, so the outcome is unknowable from the POS side. The signing queue deliberately retries
 * anyway — a Vorgang must not stay unsigned — which means the WORM can end up holding TWO
 * signatures for one Vorgang, permanently, because WORM data cannot be corrected.
 *
 * A duplicate is not itself correctable; what matters is that it is **explainable**. An auditor
 * reconciling the TSE TAR export against the DSFinV-K data will find a signed transaction the
 * export does not reference, and must be able to see why. This check raises that to the operator
 * while the period is still being closed, so the explanation is written down at the time rather
 * than reconstructed years later.
 *
 * WARNING status, not FAILED: nothing is provably wrong — the retry may well have been the only
 * signature. Failing the close over a risk that usually did not materialise would train operators
 * to click past it.
 */
class PossibleDoubleSignCheck(
    private val transactionAuditService: TransactionAuditService,
) {
    suspend fun run(
        startLdt: LocalDateTime,
        endLdt: LocalDateTime,
    ): MonthlyCloseDto.ChecklistItemDto {
        val flagged =
            transactionAuditService.getTransactionLogs(
                TransactionAuditDto.TransactionAuditQueryDto(
                    operation = TseSigningQueueServiceImpl.TSE_POSSIBLE_DOUBLE_SIGN,
                    fromDate = startLdt.toString(),
                    toDate = endLdt.toString(),
                    limit = 1000,
                ),
            )
        val count = flagged.size

        return MonthlyCloseDto.ChecklistItemDto(
            id = "no_possible_double_sign",
            status =
                if (count > 0) {
                    MonthlyCloseDto.ChecklistStatus.WARNING
                } else {
                    MonthlyCloseDto.ChecklistStatus.PASSED
                },
            detail =
                if (count > 0) {
                    "$count Vorgang/Vorgänge were re-signed after a lost TSE response — the TSE log may " +
                        "hold a second signature for them (KassenSichV / DSFinV-K Tz. 2.1)"
                } else {
                    ""
                },
            affectedCount = count,
            jumpTo = MonthlyCloseDto.SectionId.DISCREPANCIES,
            detailCode = if (count > 0) MonthlyCloseDetailCodes.POSSIBLE_DOUBLE_SIGN else null,
            detailArgs = if (count > 0) listOf(count.toString()) else emptyList(),
        )
    }
}
