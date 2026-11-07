package org.example.project.features.salesinvoice.domain.service

import java.math.BigDecimal

/** One document-level allowance (BG-20) or charge (BG-21)'s money, for [SalesInvoiceValidator]. */
data class SalesInvoiceAllowanceContent(
    val isCharge: Boolean,
    /** BT-92 / BT-99, net. */
    val amount: BigDecimal,
    val vatAmount: BigDecimal,
    val grossAmount: BigDecimal,
    val vatRate: BigDecimal,
)
