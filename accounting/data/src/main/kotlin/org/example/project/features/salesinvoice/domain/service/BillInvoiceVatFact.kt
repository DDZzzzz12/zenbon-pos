package org.example.project.features.salesinvoice.domain.service

import java.math.BigDecimal

/**
 * One `bill_vat_detail` row. Copied verbatim into the invoice's BG-23 — unless it carries a share of
 * an OWNER tip, which [OwnerTipRemoval] takes back out first: the invoice covers the meal, not the tip.
 */
data class BillInvoiceVatFact(
    /** As stored: a FRACTION (0.19), not percent units. */
    val vatRateFraction: BigDecimal,
    val netAmount: BigDecimal,
    val vatAmount: BigDecimal,
    val totalAmount: BigDecimal,
    /** The row's `Vat` entity — the key the bill folded an owner tip in by. */
    val vatId: String,
)
