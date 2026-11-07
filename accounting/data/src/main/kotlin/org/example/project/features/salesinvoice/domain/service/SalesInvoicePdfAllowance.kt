package org.example.project.features.salesinvoice.domain.service

import java.math.BigDecimal

/**
 * One printed Nachlass (allowance) or Zuschlag (charge), on the same **gross** basis as the lines, so
 * `Σ Gesamt brutto − Nachlass = Gesamtbetrag` reads true on the page. Amounts are the frozen ones.
 */
data class SalesInvoicePdfAllowance(
    val reason: String,
    val vatRatePercent: BigDecimal,
    /** As stored: positive on an invoice, negated on a Gutschrift, like the lines. */
    val grossAmount: BigDecimal,
    val isCharge: Boolean,
)
