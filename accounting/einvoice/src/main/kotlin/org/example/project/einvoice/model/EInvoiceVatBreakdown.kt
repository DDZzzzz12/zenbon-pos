package org.example.project.einvoice.model

import java.math.BigDecimal

/**
 * EN 16931 BG-23 VAT breakdown — one entry per (category, rate) pair.
 *
 * [categoryCode] is UNCL5305: S standard, Z zero-rated, E exempt, AE reverse charge,
 * K intra-community, G export, O out of scope.
 */
data class EInvoiceVatBreakdown(
    val categoryCode: String,
    val rate: BigDecimal,
    val taxableAmount: BigDecimal,
    val vatAmount: BigDecimal,
    val exemptionReasonCode: String? = null,
    val exemptionReasonText: String? = null,
)
