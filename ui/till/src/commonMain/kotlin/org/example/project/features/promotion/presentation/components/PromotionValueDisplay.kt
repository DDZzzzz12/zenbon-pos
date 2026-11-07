package org.example.project.features.promotion.presentation.components

import org.example.project.core.utils.formatCurrencyAmount
import org.example.project.ui.components.formatPercentForDisplay

/**
 * The value a promotion card shows next to its name, localized like every other amount on the till:
 * a fixed amount through the money formatter ("5,00 €"), a percentage through the percent
 * formatter ("10 %"). The picker used to print the server's raw decimal ("5.00", "10.00%" — emu16
 * NOTE-11).
 *
 * @return null for a free-item promotion, whose label is a localized string the caller supplies
 */
fun formatPromotionValue(
    discountType: String,
    discountValue: String,
    currencyCode: String,
): String? =
    when (discountType) {
        "PERCENTAGE" -> formatPercentForDisplay(discountValue)
        "FREE_ITEM" -> null
        else -> formatCurrencyAmount(discountValue, currencyCode)
    }
