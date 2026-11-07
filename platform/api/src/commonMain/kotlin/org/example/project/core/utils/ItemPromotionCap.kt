package org.example.project.core.utils

import org.example.project.money.Decimal

/**
 * Item-specific promotions (Aktionen) may stack on one order item, but together they can never take
 * the item below zero: each deduction is bounded by what is still left on the item after the
 * promotions applied to it earlier in the request. One rule for the till's estimate and the
 * server's signed bill (rule 8).
 *
 * The deduction's own base is unchanged — a percentage is still taken of the item's price, a fixed
 * amount is still its value — only the result is capped. A bill whose promotions never overlapped
 * therefore deducts exactly what it did before. A promotion that finds nothing left deducts zero.
 */
object ItemPromotionCap {
    /** What is still left on an item of [itemPrice] after [alreadyDeducted]; never negative. */
    fun remaining(
        itemPrice: Decimal,
        alreadyDeducted: Decimal,
    ): Decimal = (itemPrice - alreadyDeducted).coerceAtLeast(Decimal.ZERO)

    /** [deduction] bounded by what is still left on the item. Returns [deduction] itself when it fits. */
    fun cap(
        deduction: Decimal,
        itemPrice: Decimal,
        alreadyDeducted: Decimal,
    ): Decimal {
        val left = remaining(itemPrice, alreadyDeducted)
        return if (deduction <= left) deduction else left
    }
}
