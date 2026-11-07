package org.example.project.features.dashboard.state

import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.features.dashboard.presentation.state.toSelectionRow
import org.example.project.money.Decimal
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * One definition of what a selectable order line costs, so two screens cannot disagree.
 *
 * `TableBillingViewModel` read `chargedUnitPrice` — the per-unit gross including priced choice
 * options and the line discount — while `PosTableDetailViewModel` read the bare catalogue
 * `unitPrice`. `PosTableDetailScreen` then folds `unitPrice × quantity` for its "Bestellsumme",
 * so the panel's total came out below the sum of the very rows printed above it: a table holding
 * 37,50 € of items showed 29,50 €, short by exactly the two 4,00 € option deltas
 * (E2E-2026-09-20 #2). With an item *discount* the same expression errs the other way, too high.
 *
 * The invariant these tests pin is the one an operator can check by eye: the row total the screen
 * prints and the per-unit price it multiplies must describe the same money.
 */
@DisplayName("Order-item selection row — unit price matches the line total")
class OrderItemSelectionRowTest {
    @Test
    fun `unit price carries priced choice options, not the bare catalogue price`() {
        // 2 × Apfelsaftschorle 5,20 € with a 4,00 € "Gebratene Nudel" option: 2 × 9,20 = 18,40.
        val row = dto(unitPrice = "5.20", chargedUnitPrice = "9.20", finalPrice = "18.40", quantity = 2).toSelectionRow()

        assertEquals(
            Decimal.parse("9.20"),
            row.unitPrice,
            "the selectable row's unit price is what the guest is charged per unit, options included",
        )
    }

    @Test
    fun `unit price times quantity equals the line total the row displays`() {
        val row = dto(unitPrice = "5.20", chargedUnitPrice = "9.20", finalPrice = "18.40", quantity = 2).toSelectionRow()

        assertEquals(
            Decimal.parse(row.item.finalPrice),
            row.unitPrice * Decimal.of(row.maxQuantity),
            "the panel folds unitPrice × quantity; that must reproduce the line total printed on the row",
        )
    }

    @Test
    fun `a discounted line also reconciles`() {
        // 3 × 10,00 € at 10 % off: 9,00 € charged per unit, 27,00 € on the line.
        val row = dto(unitPrice = "10.00", chargedUnitPrice = "9.00", finalPrice = "27.00", quantity = 3).toSelectionRow()

        assertEquals(Decimal.parse("27.00"), row.unitPrice * Decimal.of(row.maxQuantity))
    }

    @Test
    fun `nothing is selected to begin with`() {
        val row = dto(unitPrice = "5.20", chargedUnitPrice = "9.20", finalPrice = "18.40", quantity = 2).toSelectionRow()

        assertEquals(0, row.selectedQuantity)
        assertEquals(Decimal.ZERO, row.totalPrice, "the selected total starts empty, not at the line total")
    }

    private fun dto(
        unitPrice: String,
        chargedUnitPrice: String,
        finalPrice: String,
        quantity: Int,
    ) = OrderItemDto.OrderItemResponseDto(
        orderItemIds = (1L..quantity).toList(),
        ordersId = setOf(1L),
        product =
            ProductDto.OrderItemProductDto(
                productId = 102L,
                productNumber = "234",
                productName = "Apfelsaftschorle 0.4L",
                en = null,
                ch = null,
                productType = "FOOD",
            ),
        unitPrice = unitPrice,
        finalPrice = finalPrice,
        chargedUnitPrice = chargedUnitPrice,
        itemStatus = "OPEN",
        vatClass = "A",
        vatRate = "19.00",
        customPrice = false,
        discount = 1.0,
        takeAway = false,
        transferred = false,
        quantity = quantity,
    )
}
