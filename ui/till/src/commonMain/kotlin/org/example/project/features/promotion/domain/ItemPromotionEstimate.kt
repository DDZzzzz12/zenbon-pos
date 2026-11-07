package org.example.project.features.promotion.domain

import org.example.project.core.utils.ItemPromotionCap
import org.example.project.features.dashboard.presentation.state.AppliedPromotion
import org.example.project.features.dashboard.presentation.state.OrderItemForSelection
import org.example.project.money.Decimal
import org.example.project.money.MoneyRounding
import org.example.project.money.Percentage

/**
 * The till's estimate of what an ITEM_SPECIFIC promotion deducts from its order item — the same
 * amount `BillServiceImpl.createBillInternal` will sign, or the payment check refuses the bill.
 *
 * The item amount is [chargedItemPrice]: the server's per-item `finalPrice` (base + priced
 * options, after the line discount), never the bare catalogue price. A fixed amount, a percentage
 * of that price (HALF_UP to the cent) or the whole price, then bounded by what [prior] promotions on
 * the same item left on it ([ItemPromotionCap], the server's rule).
 *
 * Returns null when [terms] is not an item promotion or its item is not among [rows]; the caller
 * then estimates it as before.
 */
fun estimateItemPromotionDeduction(
    terms: PromotionTerms,
    rows: List<OrderItemForSelection>,
    prior: List<AppliedPromotion>,
): Decimal? {
    if (terms.scope != "ITEM_SPECIFIC") return null
    val orderItemId = terms.orderItemId ?: return null
    val price = chargedItemPrice(orderItemId, rows) ?: return null
    val alreadyDeducted =
        prior
            .filter { it.scope == "ITEM_SPECIFIC" && it.orderItemId == orderItemId }
            .fold(Decimal.ZERO) { acc, p -> acc + p.deductedAmount }
    return ItemPromotionCap.cap(uncappedItemDeduction(terms, price), price, alreadyDeducted)
}

/**
 * The charged price of ONE order item: its row's `unitPrice`, which `toSelectionRow` takes from the
 * DTO's `chargedUnitPrice` = the server's `OrderItem.finalPrice`. A row groups order items only when
 * their `finalPrice` agrees (`OrderItemMapper` keys on it), so every id in the row has this price.
 */
fun chargedItemPrice(
    orderItemId: Long,
    rows: List<OrderItemForSelection>,
): Decimal? = rows.firstOrNull { orderItemId in it.orderItemIds }?.unitPrice

private fun uncappedItemDeduction(
    terms: PromotionTerms,
    itemPrice: Decimal,
): Decimal =
    when (terms.discountType) {
        "FIXED_AMOUNT" -> Decimal.parseOrNull(terms.discountValue) ?: Decimal.ZERO
        "PERCENTAGE" -> {
            val percentage = Percentage(Decimal.parseOrNull(terms.discountValue) ?: Decimal.ZERO)
            itemPrice.times(percentage.toFraction(), 2, MoneyRounding.HALF_UP)
        }
        "FREE_ITEM" -> itemPrice
        else -> Decimal.ZERO
    }
