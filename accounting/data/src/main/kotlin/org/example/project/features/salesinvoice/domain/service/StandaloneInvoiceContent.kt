package org.example.project.features.salesinvoice.domain.service

import java.math.BigDecimal

/**
 * The complete content of a case-C draft, derived server-side from line inputs.
 *
 * No client-supplied total survives here: every money figure is recomputed from the lines in
 * `StandaloneInvoiceMapper`, which is what keeps the validator's invariants true by construction.
 */
data class StandaloneInvoiceContent(
    val buyerName: String,
    val buyerVatId: String?,
    val buyerStreet: String?,
    val buyerZip: String?,
    val buyerCity: String?,
    val buyerCountry: String?,
    val buyerEmail: String?,
    val serviceDate: kotlinx.datetime.LocalDateTime,
    val dueDate: kotlinx.datetime.LocalDateTime?,
    val paymentTermsText: String?,
    val notes: String?,
    val lines: List<StandaloneLineContent>,
    val vatRows: List<StandaloneVatRowContent>,
    val lineNetTotal: BigDecimal,
    val taxTotal: BigDecimal,
    val taxInclusiveTotal: BigDecimal,
)

/** One derived line. [vatRate] is percent units. */
data class StandaloneLineContent(
    val itemName: String,
    val itemDescription: String?,
    val quantity: BigDecimal,
    val unitCode: String,
    /** The gross unit price the operator typed — carried verbatim so a reopen cannot lose it. */
    val grossUnitPrice: BigDecimal,
    val netUnitPrice: BigDecimal,
    val lineNetAmount: BigDecimal,
    val vatRate: BigDecimal,
    val vatCategoryCode: String,
    val vatAmount: BigDecimal,
    val lineGrossAmount: BigDecimal,
)

/** One BG-23 row, summed per rate. [vatRatePercent] is percent units. */
data class StandaloneVatRowContent(
    val vatRatePercent: BigDecimal,
    val vatCategoryCode: String,
    val taxableAmount: BigDecimal,
    val vatAmount: BigDecimal,
)
