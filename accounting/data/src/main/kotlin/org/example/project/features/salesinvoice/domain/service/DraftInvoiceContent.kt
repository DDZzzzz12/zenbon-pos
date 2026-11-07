package org.example.project.features.salesinvoice.domain.service

import kotlinx.datetime.LocalDateTime
import java.math.BigDecimal

/** What `createFromBill` writes: a complete draft, ready for `issue()` to validate. */
data class DraftInvoiceContent(
    val billId: Long,
    val billNumber: String,
    val buyer: InvoiceBuyer,
    val seller: InvoiceSeller,
    val serviceDate: LocalDateTime,
    val lines: List<DraftLine>,
    val vatRows: List<DraftVatRow>,
    /** BG-20 — one per promotion (and VAT key) the bill took off; empty for a bill without one. */
    val allowances: List<DraftAllowance>,
    /** BT-106, Σ line net: the VAT rows' taxable amounts plus the allowances they absorbed. */
    val lineNetTotal: BigDecimal,
    /** BT-107, Σ allowance net amounts; null when there is no allowance at all. */
    val allowanceTotal: BigDecimal?,
    /** BT-109 = BT-106 − BT-107, Σ of the VAT rows' taxable amounts. */
    val taxExclusiveTotal: BigDecimal,
    val taxTotal: BigDecimal,
    val taxInclusiveTotal: BigDecimal,
    val tse: DraftTseBlock?,
    val paymentNote: String,
)
