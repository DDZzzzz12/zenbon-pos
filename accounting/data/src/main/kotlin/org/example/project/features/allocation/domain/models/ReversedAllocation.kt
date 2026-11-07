package org.example.project.features.allocation.domain.models

import java.math.BigDecimal

/**
 * What a reversed allocation said, captured before its row is deleted (T9-12).
 *
 * `payment_allocation` rows are hard-deleted on reversal, so this snapshot is the only trace of the
 * assignment the operator undid. It is the value the audited `REVERSE_ALLOCATION` operation returns:
 * the audit row's `after_state` is its `toString()` and `amount_impact` its [amount].
 */
data class ReversedAllocation(
    val id: Long,
    val sourceKind: String,
    val sourceId: Long?,
    val targetKind: String,
    val targetId: Long?,
    val amount: BigDecimal,
)
