package org.example.project.features.salesinvoice.domain.service

import java.math.BigDecimal

/**
 * How a case-B invoice's total splits between what the guest already paid and what they still owe.
 *
 * Pure arithmetic, separated from the entities so the one invariant that matters can be tested
 * without a database: **`prepaid + due == total`, always.** Those are BT-113 and BT-115, which 4e's
 * EN 16931 writer emits; a `prepaid` of 0 on a bill that took €30.00 in cash would print an invoice
 * claiming the whole €80.00 is outstanding, and the guest would pay twice.
 *
 * The receivable share is clamped into `0..total` rather than trusted: a split tender is summed from
 * payment rows, and a sum that exceeds the bill (or goes negative) is a bug elsewhere that must not
 * become a negative `prepaid` on a §14 document.
 */
object ReceivableSettlement {
    data class Split(
        /** Already paid at the till — BT-113. */
        val prepaid: BigDecimal,
        /** Still owed, and the invoice's `open_amount` at issue — BT-115. */
        val due: BigDecimal,
    )

    fun split(
        total: BigDecimal,
        receivableTender: BigDecimal,
    ): Split {
        val due = receivableTender.coerceIn(BigDecimal.ZERO, total.coerceAtLeast(BigDecimal.ZERO))
        return Split(prepaid = total - due, due = due)
    }
}
