package org.example.project.features.salesinvoice.domain.service

import java.math.BigDecimal

/** One printed VAT summary row, in percent units. */
data class SalesInvoicePdfVatRow(
    val vatRatePercent: BigDecimal,
    val taxableAmount: BigDecimal,
    val vatAmount: BigDecimal,
)
