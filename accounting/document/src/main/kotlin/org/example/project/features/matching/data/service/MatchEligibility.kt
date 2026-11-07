package org.example.project.features.matching.data.service

import kotlinx.datetime.LocalDate
import java.math.BigDecimal

/**
 * Whether a bank transaction may be matched against supplier invoices at all.
 *
 * These are spec §7's suppressing conditions, applied **before** ranking rather than as a signal
 * with a negative weight: a suppressed candidate must be impossible to surface, not merely unlikely
 * to win. A wrong suggestion costs more than no suggestion, because *Übernehmen* becomes reflexive.
 *
 * The rules, each with its reason:
 * - **A reversal settles nothing** — it un-settles an earlier entry, so it has no invoice to point at.
 * - **No bank account on the statement** means no shop, so the cross-shop guard cannot even be
 *   evaluated; an unresolved statement is not matched.
 * - **A duplicate delivery** would allocate the same money twice.
 * - **Direction is a guard, not a signal.** AP matches **debits** only and AR matches **credits**
 *   only — without the split a €340 supplier invoice and a €340 customer invoice compete for the
 *   same transaction row. The default is `DEBIT`, which keeps every AP call site unchanged; the AR
 *   ranker passes `"CREDIT"`. Anything other than the asked direction is rejected, not merely the
 *   opposite one: an unrecognised direction is not *known* to be the right side.
 *
 * @param sameShop whether the invoice side belongs to this transaction's shop. **Always `true`
 *   today.** `invoice.shop_id` was specced in §3.4 but never added and the system is single-shop,
 *   so no caller can evaluate it yet. It is a parameter rather than an omission so that when the
 *   column lands the change is one call site rather than a new concept.
 */
fun isEligible(
    transaction: TransactionFacts,
    direction: String = DEBIT,
    sameShop: Boolean = true,
): Boolean {
    if (transaction.reversalIndicator) return false
    if (!transaction.hasBankAccount) return false
    if (transaction.isDuplicate) return false
    if (!transaction.creditDebit.equals(direction, ignoreCase = true)) return false
    if (!sameShop) return false
    return true
}

/**
 * The invoices among [candidates] that are still settleable, in the order they were given.
 *
 * Two drops, both properties of the invoice rather than of how well it matches:
 * - **Nothing left open.** A null [InvoiceCandidate.openAmount] means the residual is unknown and a
 *   zero or negative one means the invoice is already settled; neither can absorb a payment.
 * - **Issued before the Buchungsbeginn.** Those invoices belong to the previous system and must not
 *   be booked here. The Buchungsbeginn day itself is inside the books, so the comparison is strict.
 *   An invoice with **no** issue date is dropped too once a [bookedFrom] is set — it cannot be shown
 *   to fall inside the books, and an unprovable suggestion is worse than none. With no [bookedFrom]
 *   supplied nothing is dropped on that ground at all.
 */
fun eligibleCandidates(
    candidates: List<InvoiceCandidate>,
    bookedFrom: LocalDate?,
): List<InvoiceCandidate> =
    candidates.filter { candidate ->
        val open = candidate.openAmount
        open != null && open > BigDecimal.ZERO && isWithinBooks(candidate.issueDate, bookedFrom)
    }

internal const val DEBIT = "DEBIT"
internal const val CREDIT = "CREDIT"

private fun isWithinBooks(
    issueDate: LocalDate?,
    bookedFrom: LocalDate?,
): Boolean {
    if (bookedFrom == null) return true
    if (issueDate == null) return false
    return issueDate >= bookedFrom
}
