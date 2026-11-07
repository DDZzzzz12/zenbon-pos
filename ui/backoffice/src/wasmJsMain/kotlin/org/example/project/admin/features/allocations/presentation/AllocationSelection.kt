package org.example.project.admin.features.allocations.presentation

import org.example.project.admin.core.presentation.format.editableDecimalText
import org.example.project.core.utils.toCanonicalMoneyOrNull
import org.example.project.money.Decimal

/**
 * The pure rules behind the editor's summary bar ("Zuordnung x von y, Rest z"): which candidates of
 * the shown kind carry a valid amount, what they add up to, and whether that still fits the payment.
 * Kept free of Compose and of the view model so the rules the button and the batch share are pinned
 * by tests (project rule 8: client check and server check say the same thing).
 */
object AllocationSelection {
    /** The kinds whose candidates are picked from a list and booked in one batch. */
    val PICKED_KINDS = setOf("INVOICE", "SALES_INVOICE", "CONTRACT_INSTALMENT")

    /** `(target id, open amount)` of the candidates loaded for [kind], in list order. */
    fun candidates(
        kind: String,
        state: AllocationsState,
    ): List<Pair<Long, String>> =
        when (kind) {
            "INVOICE" -> state.openInvoices.map { it.invoiceId to it.openAmount }
            "SALES_INVOICE" -> state.openSalesInvoices.map { it.salesInvoiceId to it.openAmount }
            "CONTRACT_INSTALMENT" -> state.openInstalments.map { it.instalmentId to it.openAmount }
            else -> emptyList()
        }

    /**
     * The candidates of [kind] with a draft that may be sent: each amount passes [canonicalAmountOrNull]
     * against its **own** open amount as well as the payment's [restOpen] — an amount above the invoice's
     * residual is an over-allocation the server refuses too.
     */
    fun selected(
        kind: String,
        state: AllocationsState,
        restOpen: String,
    ): List<SelectedAllocation> =
        candidates(kind, state).mapNotNull { (id, open) ->
            val key = draftKey(kind, id)
            val amount = canonicalAmountOrNull(state.amountDrafts[key].orEmpty(), restOpen) ?: return@mapNotNull null
            if (canonicalAmountOrNull(amount, open) == null) return@mapNotNull null
            SelectedAllocation(key, id, amount)
        }

    /** True when [typed] is a positive amount above [limit] — the reason a field is refused, as opposed to merely empty or unreadable. */
    fun exceeds(
        typed: String,
        limit: String,
    ): Boolean {
        val amount = typed.toCanonicalMoneyOrNull()?.let { Decimal.parseOrNull(it) } ?: return false
        val max = Decimal.parseOrNull(limit) ?: return false
        return amount > Decimal.ZERO && amount > max
    }

    /** The candidates of [kind] whose draft is above **their own** open amount, by draft key. */
    fun overCandidate(
        kind: String,
        state: AllocationsState,
    ): Set<String> =
        candidates(kind, state)
            .filter { (id, open) -> exceeds(state.amountDrafts[draftKey(kind, id)].orEmpty(), open) }
            .map { (id, _) -> draftKey(kind, id) }
            .toSet()

    /**
     * True when the drafts (each valid against its own invoice) add up to more than the payment's
     * [restOpen]. This is what the summary bar explains; a single amount above the payment's open amount
     * counts too, although [selected] leaves it out of what would be sent.
     */
    fun overPayment(
        kind: String,
        state: AllocationsState,
        restOpen: String,
    ): Boolean {
        val rest = Decimal.parseOrNull(restOpen) ?: return false
        val total =
            candidates(kind, state).fold(Decimal.ZERO) { sum, (id, open) ->
                val typed = state.amountDrafts[draftKey(kind, id)].orEmpty()
                val amount = typed.toCanonicalMoneyOrNull()?.let { Decimal.parseOrNull(it) }
                if (amount == null || amount <= Decimal.ZERO || exceeds(typed, open)) sum else sum + amount
            }
        return total > rest
    }

    /** What the selection adds up to, in [Decimal]. */
    fun total(selected: List<SelectedAllocation>): Decimal =
        selected.fold(Decimal.ZERO) { sum, item -> sum + (Decimal.parseOrNull(item.amount) ?: Decimal.ZERO) }

    /** The payment's residual not covered by [total]: negative when the selection is too large. */
    fun remaining(
        restOpen: String,
        total: Decimal,
    ): Decimal? = Decimal.parseOrNull(restOpen)?.let { it - total }

    /** True when something is selected and it does not exceed the payment's [restOpen]. */
    fun canAssign(
        selected: List<SelectedAllocation>,
        restOpen: String,
    ): Boolean {
        if (selected.isEmpty()) return false
        val rest = remaining(restOpen, total(selected)) ?: return false
        return rest.signum >= 0
    }

    /**
     * The amount ticking a candidate fills in: its own open amount, but at most what the payment still
     * has left after the other drafts ([alreadyDrafted]). Null when nothing is left or an amount does
     * not parse — the box then stays empty rather than receiving a made-up figure.
     */
    fun prefill(
        candidateOpen: String,
        restOpen: String,
        alreadyDrafted: Decimal,
    ): String? {
        val open = Decimal.parseOrNull(candidateOpen) ?: return null
        val left = Decimal.parseOrNull(restOpen)?.minus(alreadyDrafted) ?: return null
        val fill = if (open < left) open else left
        // Two places like every other money field (the wire carries four); never below the cent, never above the cap.
        return if (fill.signum > 0) editableDecimalText(fill.toPlainString(), minFractionDigits = 2) else null
    }
}
