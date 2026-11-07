package org.example.project.features.bill.domain

/** What decides whether a bill may be rebooked by a Zahlartwechsel, read from the bill in one place. */
data class RebookFacts(
    val isStandingSale: Boolean,
    /** A counter gift-voucher sale on a virtual table. */
    val isVirtualTable: Boolean,
    /** The bill sells a gift voucher (`VOU` item) — its Storno invalidates the issued voucher. */
    val hasVoucherItem: Boolean,
    /** A payment receipt for an existing invoice, not a meal. */
    val clearsInvoice: Boolean,
)
