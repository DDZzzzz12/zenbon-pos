package org.example.project.features.allocation.domain.models

/**
 * Where the money came from.
 *
 * The cash book is a **source**, not only a target: a supplier invoice paid from the till is a
 * `kassenbuch_header` of type `CASH_EXPENSE`, and without this the invoice stays unpaid forever.
 *
 * Stored as its [name] in `payment_allocation.source_kind` — renaming a constant orphans every row
 * that used the old name, so `AllocationKindTest` pins these.
 */
enum class AllocationSourceKind {
    BANK_TRANSACTION,
    CASH_BOOK_ENTRY,

    /**
     * A payment taken at the till on a **clearing Bill** (slice 4c-2c): the guest settled an open
     * receivable at the counter.
     *
     * Neither of the other two fits — the money did not arrive on a bank statement, and a card
     * clearing has no place in a cash book — so this kind carries its own reference,
     * `payment_allocation.bill_id`.
     */
    TILL_RECEIPT,
}
