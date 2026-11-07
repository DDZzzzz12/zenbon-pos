package org.example.project.features.salesinvoice.domain.service

import java.math.BigDecimal

/** One invoice line. [vatRatePercent] is percent units — 19.00, never 0.19. */
data class DraftLine(
    val lineNo: Int,
    val itemName: String,
    val quantity: BigDecimal,
    val netUnitPrice: BigDecimal,
    val netAmount: BigDecimal,
    val vatAmount: BigDecimal,
    val grossAmount: BigDecimal,
    val vatRatePercent: BigDecimal,
    val sourceOrderItemId: Long,
)
