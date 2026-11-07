package org.example.project.features.statistics.data.repository

import java.math.BigDecimal

/**
 * The two figures that take the Katalog's category column to the Umsatz tile: the part of the
 * promotions that sits on no line (bill-wide Aktionen), and the owner tips, which are turnover but
 * belong to no product.
 */
internal data class CatalogReconciliation(
    val billWidePromotions: BigDecimal,
    val ownerTips: BigDecimal,
)
