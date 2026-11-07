package org.example.project.features.salesinvoice.domain.service

import java.math.BigDecimal

/** One BG-23 row. [vatRatePercent] is percent units — 19.00, never 0.19. */
data class DraftVatRow(
    val vatRatePercent: BigDecimal,
    val taxableAmount: BigDecimal,
    val vatAmount: BigDecimal,
)
