package org.example.project.ui.components.zBon

import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.utils.formatCurrencyAmount

/**
 * One row of a Z-Bon's per-promotion breakdown ("Aktionen"), ready to display: the promotion's name,
 * how often it was applied ("3×") and what it deducted in the shop's currency.
 *
 * The paper and PDF Z-Bon print this table; both the till's and the back office's Z-Bon panel render
 * it from [zBonPromotionLines], so the three cannot disagree on what a promotion cost.
 */
data class ZBonPromotionLine(
    val name: String,
    val timesApplied: String,
    val totalDeducted: String,
)

/**
 * The Z-Bon's promotion breakdown as display rows, in the order the server sent them. Empty when the
 * Z-Bon carries none (a pre-promotion Z-Bon sends `null`), which hides the section.
 */
fun zBonPromotionLines(
    zBon: ZBonDto.ZBonResponseDto,
    currencyCode: String,
): List<ZBonPromotionLine> =
    zBon.promotionBreakdown.orEmpty().map { promo ->
        ZBonPromotionLine(
            name = promo.promotionName,
            timesApplied = "${promo.timesApplied}×",
            totalDeducted = formatCurrencyAmount(promo.totalDeducted, currencyCode),
        )
    }
