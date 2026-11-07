package org.example.project.features.allocation.domain.models

/**
 * What the money settled.
 *
 * `INVOICE`, `CONTRACT_INSTALMENT` and `SALES_INVOICE` name a row that carries its own residual — a
 * supplier invoice, an expected payment under a contract, and an outgoing invoice the customer owes
 * us (the AR mirror of `INVOICE`) — and all three are reachable: each has a real foreign key on
 * `payment_allocation` and the service resolves it.
 *
 * The other four are terminal: they settle the payment without any counterpart residual to reduce.
 * Two of them still require a counter-leg id, resolved only to prove the row exists — `CASH_TRANSIT`
 * is the cash book as a *target*: the bank leg of a Geldtransit, pointing at the matching
 * `CASH_DEPOSIT` / `CASH_WITHDRAWAL` entry; `INTERNAL_TRANSFER` names the other bank account of a
 * move between two of our own. `EXPENSE_NO_DOC` needs no id but does require a booking text — with
 * no document it is the only record of what the money was — while `FEE` requires nothing, being
 * self-evident from the transaction it sits on.
 *
 * Stored as its [name] in `payment_allocation.target_kind` — renaming a constant orphans every row
 * that used the old name, so `AllocationKindTest` pins these.
 */
enum class AllocationTargetKind {
    INVOICE,
    CONTRACT_INSTALMENT,

    /** An outgoing (STANDALONE) invoice the customer owes us — the AR mirror of [INVOICE]. */
    SALES_INVOICE,
    CASH_TRANSIT,
    INTERNAL_TRANSFER,
    EXPENSE_NO_DOC,
    FEE,
}
