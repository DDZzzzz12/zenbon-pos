package org.example.project.features.salesinvoice.domain.service

import java.math.BigDecimal

/**
 * One sold item. There is no quantity column on `t_order_item` — a row is one unit — so quantity is
 * always 1 and [grossAmount] is that unit's `final_price`.
 */
data class BillInvoiceLineFact(
    val orderItemId: Long,
    val itemName: String,
    val grossAmount: BigDecimal,
    /** As stored: a FRACTION (0.19), not percent units. The mapper converts. */
    val vatRateFraction: BigDecimal,
    /** The item's `Vat` entity — the key the bill folded an owner tip in by (two entities may share a rate). */
    val vatId: String,
)
