package org.example.project.features.matching.data.service

import java.math.BigDecimal

/**
 * One allocation the reversal sweep removed because the bank entry it paid from was returned.
 *
 * Captured **before** the delete, from the stored row, so the caller can say afterwards what was
 * undone — an allocation that disappears without a trace is exactly the silent un-payment the
 * sweep's audit record exists to prevent.
 *
 * @property reversedTransactionId the original debit (or credit) whose allocation was voided.
 * @property reversalTransactionId the returned entry that reversed it — the Rücklastschrift.
 * @property targetKind `AllocationTargetKind` name of what the money had been assigned to.
 * @property targetId the invoice, outgoing invoice or contract instalment that is open again, or
 *   null for a terminal target (fee, expense, transfer) that carries no residual.
 */
data class VoidedAllocation(
    val allocationId: Long,
    val reversedTransactionId: Long,
    val reversalTransactionId: Long,
    val targetKind: String,
    val targetId: Long?,
    val amount: BigDecimal,
)
