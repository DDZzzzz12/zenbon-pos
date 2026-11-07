package org.example.project.features.salesinvoice.domain.service

import java.math.BigDecimal

/**
 * What the validator needs to know about an invoice about to be issued.
 *
 * A record rather than the DAO entity, so the rules are pure and can be tested without a database —
 * and so a caller cannot accidentally have the validator lazy-load half the object graph.
 */
data class SalesInvoiceContent(
    val buyerName: String,
    val hasServiceDate: Boolean,
    val lines: List<SalesInvoiceLineContent>,
    val vatRows: List<SalesInvoiceVatContent>,
    val taxInclusiveTotal: BigDecimal?,
    val taxTotal: BigDecimal?,
    /** BG-20 / BG-21 — empty for an invoice without document-level allowances or charges. */
    val allowances: List<SalesInvoiceAllowanceContent> = emptyList(),
    /** BT-106. */
    val lineNetTotal: BigDecimal? = null,
    /** BT-107; null reads as zero. */
    val allowanceTotal: BigDecimal? = null,
    /** BT-108; null reads as zero. */
    val chargeTotal: BigDecimal? = null,
    /** BT-109. */
    val taxExclusiveTotal: BigDecimal? = null,
)

/** One line's money, for [SalesInvoiceValidator]. */
data class SalesInvoiceLineContent(
    val netAmount: BigDecimal,
    val vatAmount: BigDecimal,
    val grossAmount: BigDecimal,
    val vatRate: BigDecimal,
)

/** One BG-23 row's money, for [SalesInvoiceValidator]. */
data class SalesInvoiceVatContent(
    val vatRate: BigDecimal,
    val taxableAmount: BigDecimal,
    val vatAmount: BigDecimal,
)
