package org.example.project.core.domain.dto.statistics

import kotlinx.serialization.Serializable

/**
 * VAT summary for tax reporting.
 */
@Serializable
data class VatSummaryDto(
    /** VAT rate percentage */
    val vatRate: String,
    /** Net amount before VAT */
    val netAmount: String,
    /** VAT amount */
    val vatAmount: String,
    val totalAmount: String,
    /** Number of transactions with this VAT rate */
    val transactionCount: Int,
)
