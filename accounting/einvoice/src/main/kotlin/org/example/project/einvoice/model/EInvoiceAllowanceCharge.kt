package org.example.project.einvoice.model

import java.math.BigDecimal

/** EN 16931 BG-20 allowance / BG-21 charge at document level. */
data class EInvoiceAllowanceCharge(
    val isCharge: Boolean,
    val amount: BigDecimal,
    val vatCategoryCode: String,
    val vatRate: BigDecimal,
    val baseAmount: BigDecimal? = null,
    val percentage: BigDecimal? = null,
    val reasonCode: String? = null,
    val reasonText: String? = null,
)
