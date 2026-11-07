package org.example.project.features.matching.data.service

import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import org.example.project.features.supplier.data.service.normalizeSupplierName
import kotlin.math.abs

/**
 * How far from an invoice's due date a payment may fall and still be read as settling it.
 *
 * Ten days each way: a direct debit clears within a few working days of the due date, and a bank
 * transfer paid on receipt of a reminder lands a little after. Ten covers both without reaching into
 * the next month's invoice from the same supplier — which, for a monthly biller, is what a wider
 * window would start doing. The boundary itself is inside (`<=`).
 *
 * Deliberately its own constant rather than shared with `ContractMatchRanker`'s: that one is about a
 * contract instalment's due date, and the two policies should be able to move independently even
 * though they agree on ten today.
 */
private const val WINDOW_DAYS = 10

/**
 * Spec §7's signal **6**: the amount is the invoice's open amount exactly, the counterparty name
 * agrees with the supplier's after normalisation, and the payment landed near the due date.
 *
 * `MEDIUM`, and never more. All three legs are circumstantial — no identifier appears anywhere in
 * it — so this is a plausible reading offered to a human, not a conclusion.
 *
 * **"Fuzzy" here means the normalised keys match**, not an edit distance. [normalizeSupplierName] —
 * the same function that produced the stored `supplier.normalized_name`, called rather than copied —
 * already makes "Metro Cash & Carry GmbH" and "METRO Cash Carry" agree, which is the variation real
 * bank statements carry. An edit distance would go further and make *different* companies agree, and
 * at this strength, with no identifier to catch it, that error would reach the operator looking
 * exactly like a correct suggestion. Distance-based matching is a decision worth taking on its own
 * evidence, not a detail of this signal.
 *
 * Pure: no database, no clock. The caller loads the candidates and the stored keys.
 *
 * @param supplierNormalizedNames each candidate supplier's stored `normalized_name`, keyed by
 *   supplier id. Reading it is a database lookup, so the caller does it and passes the answer in, as
 *   [InvoiceMatchRanker] does with the IBAN's supplier. A supplier absent from the map is skipped —
 *   there is no key to compare against, and "no key" is not agreement.
 */
fun supplierNameSuggestions(
    transaction: TransactionFacts,
    candidates: List<InvoiceCandidate>,
    supplierNormalizedNames: Map<Long, String>,
): List<MatchSuggestion> {
    // The guards are a filter, not a weak signal — re-asserted so a caller that forgets them cannot
    // get a suggestion anyway.
    if (!isEligible(transaction)) return emptyList()

    val paidByKey = transaction.counterpartyName?.takeIf { it.isNotBlank() }?.let { normalizeSupplierName(it) }
    if (paidByKey.isNullOrBlank()) return emptyList()

    return candidates
        .filter { it.agreesOnName(paidByKey, supplierNormalizedNames) }
        .filter { it.agreesOnAmount(transaction) && it.isNearDueDate(transaction.valueDate) }
        .map { candidate ->
            MatchSuggestion(
                invoiceIds = listOf(candidate.id),
                signal = MatchSignal.SUPPLIER_NAME_AMOUNT_DATE,
                strength = MatchStrength.MEDIUM,
                amount = transaction.amount,
            )
        }
}

private fun InvoiceCandidate.agreesOnName(
    paidByKey: String,
    supplierNormalizedNames: Map<Long, String>,
): Boolean {
    val storedKey = supplierId?.let { supplierNormalizedNames[it] } ?: return false
    return storedKey.isNotBlank() && storedKey == paidByKey
}

/**
 * Exactly the open amount — signal 6 carries no tolerance band. The arithmetic is the only thing
 * distinguishing this invoice from the supplier's others, so loosening it would remove the signal's
 * one discriminating leg. `compareTo`, never `equals`, which is scale-sensitive.
 */
private fun InvoiceCandidate.agreesOnAmount(transaction: TransactionFacts): Boolean {
    val open = openAmount ?: return false
    return open.compareTo(transaction.amount) == 0
}

private fun InvoiceCandidate.isNearDueDate(valueDate: LocalDate): Boolean {
    val due = dueDate ?: return false
    return abs(due.daysUntil(valueDate)) <= WINDOW_DAYS
}
