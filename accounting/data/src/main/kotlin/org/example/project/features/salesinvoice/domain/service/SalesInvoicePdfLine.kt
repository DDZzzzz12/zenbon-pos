package org.example.project.features.salesinvoice.domain.service

import java.math.BigDecimal
import java.math.RoundingMode

/** One printed line. Amounts are the frozen ones; nothing is recomputed at render time. */
data class SalesInvoicePdfLine(
    val lineNo: Int,
    val itemName: String,
    val quantity: BigDecimal,
    val netUnitPrice: BigDecimal,
    val grossAmount: BigDecimal,
    val vatRatePercent: BigDecimal,
    /** The line's gross unit price as captured; null (or zero on a row older than the column) falls back to gross ÷ quantity. */
    val grossUnitPrice: BigDecimal? = null,
) {
    /**
     * The unit price printed beside [grossAmount] — on the **same (gross) basis**, so `Menge ×
     * Einzelpreis = Gesamt` reads true on the page. It carries the line's sign, so a Gutschrift row
     * reads `1 × −238,00 = −238,00` rather than a positive price beside a negative total.
     */
    val displayGrossUnitPrice: BigDecimal
        get() {
            val captured = grossUnitPrice?.takeIf { it.signum() != 0 }
            val unit =
                captured
                    ?: if (quantity.signum() == 0) grossAmount else grossAmount.divide(quantity, 4, RoundingMode.HALF_UP)
            return if (grossAmount.signum() < 0 && unit.signum() > 0) unit.negate() else unit
        }
}
