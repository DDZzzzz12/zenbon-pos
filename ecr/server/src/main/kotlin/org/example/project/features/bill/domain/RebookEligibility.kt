package org.example.project.features.bill.domain

/**
 * The rebook rule, shared by the mapper's `isRebookable` flag (the till shows the action) and the
 * service's refusal (the server rejects it) — one rule for both levels (CLAUDE.md rule 8).
 */
object RebookEligibility {
    enum class Refusal { NOT_A_STANDING_SALE, UNSUPPORTED }

    fun refusal(facts: RebookFacts): Refusal? =
        when {
            !facts.isStandingSale -> Refusal.NOT_A_STANDING_SALE
            facts.isVirtualTable || facts.hasVoucherItem || facts.clearsInvoice -> Refusal.UNSUPPORTED
            else -> null
        }
}
