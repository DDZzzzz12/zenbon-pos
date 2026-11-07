package org.example.project.features.matching.data.service

import kotlinx.datetime.daysUntil

/**
 * Which earlier entry among [candidates] the returned direct debit [reversal] undoes, or null when
 * that cannot be told.
 *
 * The bank sends a new entry flagged `reversal_indicator` and does not reliably say what it reverses,
 * so this is a matching problem and gets the suggestion ladder's discipline: **strong identifiers
 * first, and silence when unsure.**
 *
 * 1. the same [TransactionFacts.endToEndId] — the payer's own reference, carried through the return.
 *    Amounts may differ: a partial return is still a return.
 * 2. the same [TransactionFacts.accountServicerReference] — the bank's own entry reference.
 * 3. no identifier at all: the same counterparty IBAN, the same amount, the **opposite** direction,
 *    and within [REVERSAL_WINDOW_DAYS] before the reversal.
 *
 * **No pairing means no voiding.** The caller leaves such a reversal in the queue for a human.
 * Voiding the wrong entry would un-pay an invoice that *was* paid, creating exactly the invisible
 * wrong state this rule exists to prevent, in the opposite direction.
 *
 * Every signal requires its winner to be **unique**. Under signal 3 that is the load-bearing part —
 * two identical direct debits to one supplier in a month are ordinary, and picking either is a coin
 * flip. It is applied to the identifiers too: a reference the bank has reused identifies nothing, and
 * guessing there would be the same coin flip with a more convincing-looking reason.
 *
 * Pure over values, so it is tested without a database. Money is compared with `compareTo`, never
 * `equals`: `BigDecimal("119.00") != BigDecimal("119.0")` though the amounts are the same, and a
 * scale difference must not decide whether an invoice goes back to open.
 */
fun findReversedEntry(
    reversal: TransactionFacts,
    candidates: List<TransactionFacts>,
): TransactionFacts? {
    val eligible = candidates.filter { it.id != reversal.id && !it.reversalIndicator }
    if (eligible.isEmpty()) return null

    matchedByIdentifier(eligible, reversal.endToEndId) { it.endToEndId }?.let { return it }
    matchedByIdentifier(eligible, reversal.accountServicerReference) { it.accountServicerReference }
        ?.let { return it }
    return matchedUniquelyBy(eligible) { it.isUnidentifiedCounterpartOf(reversal) }
}

/**
 * How long before a reversal its original may lie.
 *
 * A returned direct debit comes back within days — the SEPA Core return period is five business
 * days, and an unauthorised-transaction refund thirteen months. Thirty days sits above the first and
 * far below the second on purpose: signal 3 has no identifier behind it, so widening the window
 * gathers ambiguity rather than matches, and every extra candidate it pulls in makes silence more
 * likely, not less. A return the bank sends later than that keeps its `end_to_end_id`, which is
 * signal 1's business.
 */
internal const val REVERSAL_WINDOW_DAYS = 30

/**
 * The one candidate whose [identifierOf] equals the reversal's [reference].
 *
 * A **blank or absent** reference on either side matches nothing at all. Without that guard the two
 * entries the bank left empty would "share" a reference and pair with each other — a null equalling
 * a null is the easiest way to invent a match out of missing data.
 */
private fun matchedByIdentifier(
    candidates: List<TransactionFacts>,
    reference: String?,
    identifierOf: (TransactionFacts) -> String?,
): TransactionFacts? {
    val key = reference?.takeIf { it.isNotBlank() } ?: return null
    return matchedUniquelyBy(candidates) { identifierOf(it) == key }
}

/**
 * The candidate matched by [predicate], but **only when exactly one** is. Two matches are no match:
 * see the uniqueness note on [findReversedEntry].
 */
private fun matchedUniquelyBy(
    candidates: List<TransactionFacts>,
    predicate: (TransactionFacts) -> Boolean,
): TransactionFacts? = candidates.filter(predicate).singleOrNull()

/**
 * Signal 3 — same counterparty, same money, coming back the other way, recently enough.
 *
 * The opposite direction is what separates a return from an ordinary second payment: a debit
 * followed by another debit to the same supplier for the same amount is two payments, not one
 * undone. The original must not lie *after* the reversal either — an entry the bank made later
 * cannot be the one this undoes.
 */
private fun TransactionFacts.isUnidentifiedCounterpartOf(reversal: TransactionFacts): Boolean {
    val iban = counterpartyIban.orNull() ?: return false
    if (iban != reversal.counterpartyIban.orNull()) return false
    if (amount.compareTo(reversal.amount) != 0) return false
    if (creditDebit.equals(reversal.creditDebit, ignoreCase = true)) return false
    return valueDate.daysUntil(reversal.valueDate) in 0..REVERSAL_WINDOW_DAYS
}

/**
 * A blank reference is an absent one. Without this, two entries the bank left empty would "share" a
 * reference and pair with each other.
 */
private fun String?.orNull(): String? = this?.takeIf { it.isNotBlank() }
