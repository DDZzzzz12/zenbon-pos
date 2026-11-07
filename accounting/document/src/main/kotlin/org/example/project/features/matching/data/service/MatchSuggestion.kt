package org.example.project.features.matching.data.service

import java.math.BigDecimal

/**
 * One thing a payment might be settling, and why we think so.
 *
 * [invoiceIds] is a **list**, not a single id, because signal 7 matches a *set* of invoices against
 * one payment. A suggestion type that could only name one invoice would make that signal
 * unrepresentable — and a several-invoices-in-one-transfer payment is exactly what an operator most
 * wants help unpicking.
 *
 * [amount] is what the payment would settle: the payment itself, or the invoices' open amount when
 * that is smaller. It is `BigDecimal`, and every comparison against it uses `compareTo`.
 *
 * A suggestion is never applied by itself. It is offered, and a human accepts it.
 *
 * **Exactly one of [invoiceIds] (non-empty) and [contractInstalmentId] (non-null) is set**, and the
 * `init` block enforces it rather than the KDoc merely asking for it. Signals 3 and 5 point at a
 * contract instalment, not an invoice; a second suggestion type would have duplicated the merging
 * and the sorting, so the one type names a typed id per target kind — which is `payment_allocation`'s
 * own shape, and the convention this whole feature already follows. A suggestion naming both would
 * be two answers to one question, and one naming neither would be an allocation with no target.
 */
data class MatchSuggestion(
    val invoiceIds: List<Long>,
    val signal: MatchSignal,
    val strength: MatchStrength,
    val amount: BigDecimal,
    val contractInstalmentId: Long? = null,
) {
    init {
        require(invoiceIds.isNotEmpty() != (contractInstalmentId != null)) {
            "a suggestion names either invoices or one contract instalment, never both and never neither: " +
                "invoiceIds=$invoiceIds, contractInstalmentId=$contractInstalmentId"
        }
    }
}
