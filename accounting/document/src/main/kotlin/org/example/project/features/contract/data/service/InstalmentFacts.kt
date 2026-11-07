package org.example.project.features.contract.data.service

import kotlinx.datetime.LocalDate
import java.math.BigDecimal

/**
 * Everything the alert derivation needs to know about one instalment.
 *
 * Plain values, not an entity: the caller reads them once, in batch, and the derivation never
 * touches the database.
 *
 * @param storedStatus the column's value — only `OPEN` and `WAIVED` are ever stored, and a `WAIVED`
 *   instalment raises nothing at all.
 * @param expectedAmount what this period was expected to cost; falls back to the contract's when null.
 * @param openAmount the residual. `null` means nothing has touched the instalment yet, which is
 *   still money that has not gone out — the same reading [deriveInstalmentStatus] gives it.
 * @param hasInvoice whether an invoice is linked at all. Separate from [invoiceTotal] on purpose: an
 *   invoice can be linked and still state no total — a hand-captured one often does — and collapsing
 *   the two would report `INVOICE_MISSING` for a document sitting right there.
 * @param invoiceTotal the linked invoice's `amount_due ?: tax_inclusive_total`, `null` when there is
 *   no invoice **or** when the invoice states no total. It is the actual amount for an
 *   invoice-bearing contract; presence is [hasInvoice]'s job, not this field's.
 * @param allocatedTotal the sum actually allocated to this instalment; `null` (or zero) when nothing
 *   has been. It is the actual amount for an invoice-less contract, where the payment is the only
 *   evidence of what the obligation cost.
 */
data class InstalmentFacts(
    val id: Long,
    val dueDate: LocalDate,
    val storedStatus: String,
    val expectedAmount: BigDecimal?,
    val openAmount: BigDecimal?,
    val hasInvoice: Boolean,
    val invoiceTotal: BigDecimal?,
    val allocatedTotal: BigDecimal?,
)
