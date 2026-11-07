package org.example.project.features.promotion.domain

import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.features.dashboard.presentation.state.AppliedPromotion
import org.example.project.features.dashboard.presentation.state.OrderItemForSelection
import org.example.project.features.dashboard.presentation.state.toSelectionRow
import org.example.project.money.Decimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The till's item-promotion estimate must deduct what the server signs: on the order item's
 * CHARGED price (the server's `finalPrice`, incl. priced options and the line discount), capped at
 * what earlier promotions on the item left. Each case mirrors `BillItemPromotionStackingTest`.
 */
class ItemPromotionEstimateTest {
    private val itemId = 101L

    /** A row as the server sends it: catalogue [unitPrice], charged [chargedUnitPrice] per unit. */
    private fun row(
        unitPrice: String,
        chargedUnitPrice: String,
    ) = OrderItemDto
        .OrderItemResponseDto(
            orderItemIds = listOf(itemId, itemId + 1),
            ordersId = setOf(1L),
            product = ProductDto.OrderItemProductDto(productId = 7L, productNumber = "P7", productName = "Pizza", productType = "FOOD"),
            unitPrice = unitPrice,
            finalPrice = (Decimal.parse(chargedUnitPrice) * Decimal.of(2)).toPlainString(),
            chargedUnitPrice = chargedUnitPrice,
            itemStatus = "OPEN",
            vatClass = "1",
            vatRate = "0.19",
            customPrice = false,
            discount = 1.0,
            takeAway = false,
            transferred = false,
            quantity = 2,
        ).toSelectionRow()

    private fun terms(
        type: String,
        value: String,
    ) = PromotionTerms(discountType = type, discountValue = value, scope = "ITEM_SPECIFIC", orderItemId = itemId)

    /** Applies [promotions] in order, each estimated against the ones before — as the till does. */
    private fun applyInOrder(
        rows: List<OrderItemForSelection>,
        vararg promotions: PromotionTerms,
    ): List<String> =
        promotions
            .fold(emptyList<AppliedPromotion>()) { prior, terms ->
                val deducted = estimateItemPromotionDeduction(terms, rows, prior)!!
                prior +
                    AppliedPromotion(
                        promotionId = prior.size + 1L,
                        promotionName = "P${prior.size + 1}",
                        discountType = terms.discountType,
                        discountValue = terms.discountValue,
                        scope = terms.scope,
                        orderItemId = terms.orderItemId,
                        deductedAmount = deducted,
                    )
            }.map { it.deductedAmount.setScale(2).toPlainString() }

    @Test
    fun `two fixed 8 euro promotions on a 10 euro item with 2 euro of extras deduct 8 and 4`() {
        val rows = listOf(row(unitPrice = "10.00", chargedUnitPrice = "12.00"))
        assertEquals(listOf("8.00", "4.00"), applyInOrder(rows, terms("FIXED_AMOUNT", "8.00"), terms("FIXED_AMOUNT", "8.00")))
    }

    @Test
    fun `a percentage is taken of the charged price including extras`() {
        val rows = listOf(row(unitPrice = "10.00", chargedUnitPrice = "12.00"))
        assertEquals(listOf("6.00"), applyInOrder(rows, terms("PERCENTAGE", "50.00")))
    }

    @Test
    fun `a free item is the charged price after the line discount`() {
        // 10.00 with a 10 % line discount: the server's finalPrice is 9.00.
        val rows = listOf(row(unitPrice = "10.00", chargedUnitPrice = "9.0000"))
        assertEquals(listOf("3.00", "6.00"), applyInOrder(rows, terms("FIXED_AMOUNT", "3.00"), terms("FREE_ITEM", "0")))
    }

    @Test
    fun `a third promotion that finds nothing left deducts zero`() {
        val rows = listOf(row(unitPrice = "10.00", chargedUnitPrice = "10.00"))
        assertEquals(
            listOf("4.00", "6.00", "0.00"),
            applyInOrder(rows, terms("FIXED_AMOUNT", "4.00"), terms("FIXED_AMOUNT", "6.00"), terms("FIXED_AMOUNT", "5.00")),
        )
    }

    @Test
    fun `a bill-wide promotion or an unknown item is not an item estimate`() {
        val rows = listOf(row(unitPrice = "10.00", chargedUnitPrice = "10.00"))
        assertNull(estimateItemPromotionDeduction(terms("FIXED_AMOUNT", "1.00").copy(scope = "BILL_WIDE"), rows, emptyList()))
        assertNull(estimateItemPromotionDeduction(terms("FIXED_AMOUNT", "1.00").copy(orderItemId = 999L), rows, emptyList()))
    }
}
