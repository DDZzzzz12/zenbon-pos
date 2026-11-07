package org.example.project.features.bill.domain.service

import org.example.project.core.domain.enums.tse.TseLateSigningReason
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.infrastructure.errors.PosException

/**
 * How a sign attempt ended and what the caller must do next.
 *
 * - [transaction]: the TSE transaction to link to the entity, or null when nothing was signed.
 * - [status]: the signing status the caller writes on the entity.
 * - [enqueue]: the caller hands the Vorgang to the Nachsignierung queue ([SaleSigningGateway.enqueue]).
 * - [rethrow]: FAIL mode — the caller rejects the Vorgang; the original signing error.
 * - [lateReason]/[lateReasonText]: the permanent Nachsignierung cause, set **only** when [enqueue].
 */
data class SaleSigningAttempt(
    val transaction: TseTransaction?,
    val status: TseSigningStatus,
    val enqueue: Boolean,
    val rethrow: PosException?,
    val lateReason: TseLateSigningReason?,
    val lateReasonText: String?,
)
