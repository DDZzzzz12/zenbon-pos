package org.example.project.features.salesinvoice.domain.service

import org.example.project.core.domain.enums.bill.BillKind
import java.math.BigDecimal

/**
 * What the guard needs to know about the bill an invoice claims to render.
 *
 * [vatDetailTotal] is `Σ bill_vat_detail.total_amount` — the fiscal split the receipt printed. Less
 * [ownerTip] it is the meal, the anchor for the amount check. Not `paid_amount`, which includes every
 * tip: a Trinkgeld is not part of the invoiced supply. An EMPLOYEE tip has no VAT row at all; an OWNER
 * tip is taxable revenue the bill folded into its VAT rows, so it is subtracted here. [billAmount] is
 * carried only to cross-check that the bill agrees with itself.
 *
 * [kind] and [isCancelled] say whether the bill is a standing sale at all: only the original of a
 * sale that was not stornoed may be invoiced — never a Storno bill, a cancelled original or a
 * receivable clearing.
 */
data class BillInvoiceabilityFacts(
    val billId: Long,
    val kind: BillKind,
    val isCancelled: Boolean,
    val isTraining: Boolean,
    val billAmount: BigDecimal,
    /** `bill.promotion_amount`: [billAmount] is before promotions, [vatDetailTotal] after them. */
    val promotionAmount: BigDecimal,
    val vatDetailTotal: BigDecimal,
    /** The OWNER tip folded into the VAT rows; zero for an employee tip or none. */
    val ownerTip: BigDecimal,
    val hasLiveInvoice: Boolean,
    /**
     * Σ the bill's COMPLETED payment legs tendered "auf Rechnung" (`createsReceivable`). More than the
     * meal means part of the tip is on account, which the invoice cannot carry.
     */
    val receivableTender: BigDecimal,
)
