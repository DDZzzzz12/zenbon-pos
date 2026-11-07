package org.example.project.features.reports.presentation.state

import org.example.project.core.domain.dto.report.ZBonDto

/**
 * The storno of one booked tip payout the operator is about to confirm.
 *
 * [reason] is required, like every Kassenbuch correction — it is printed on the Eigenbeleg. The
 * server repeats every guard (not yet reversed, really a payout of that closing) under its lock.
 *
 * @property isSubmitting true while the request is in flight, so a double tap cannot storno twice
 */
data class TipReversalDraft(
    val zBonId: Long,
    val employeeId: String,
    val employeeName: String,
    val payout: ZBonDto.TipPayoutRecordDto,
    val reason: String = "",
    val isSubmitting: Boolean = false,
) {
    val canConfirm: Boolean get() = reason.isNotBlank() && !isSubmitting && !payout.reversed
}
