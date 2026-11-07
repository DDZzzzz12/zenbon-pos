package org.example.project.utils

import org.example.project.features.bill.domain.models.Bill

/**
 * Whether a Beleg must carry `bill_vat_detail` rows of its own — the one definition the DSFinV-K
 * pre-export check ("N bills are missing VAT breakdown details") counts against.
 *
 * Two kinds of Beleg carry none BY DESIGN, and warning about them told the operator a package
 * that reconciles was incomplete:
 *
 * - a **Forderungsausgleich** (`clearsSalesInvoiceId` set): the VAT was due on the invoice; the export
 *   synthesises one rate-less key-5 Forderungsaufloesung line instead.
 * - a **storno** (`deletedBill` set) whose sale carried none: `deleteBill` copies the negated rows
 *   of the original, so it expects rows iff the original did.
 *
 * Every other Beleg is a sale and must carry rows. Requires an ambient transaction (the storno leg
 * reads the original).
 */
object BillVatRowExpectation {
    fun requiresVatRows(bill: Bill): Boolean {
        if (bill.clearsSalesInvoiceId != null) return false
        val original = bill.deletedBill ?: return true
        return requiresVatRows(original)
    }
}
