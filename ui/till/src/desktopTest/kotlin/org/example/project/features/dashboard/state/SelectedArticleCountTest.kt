package org.example.project.features.dashboard.state

import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.features.dashboard.presentation.state.OrderItemForSelection
import org.example.project.features.dashboard.presentation.state.TableBillingUiState
import org.example.project.features.dashboard.presentation.state.selectedArticleCount
import org.example.project.features.dashboard.presentation.state.toSelectionRow
import org.example.project.money.Decimal
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * OBS-3: the billing footer said "Ausgewählt: 4 Artikel" while the review panel said "6 Artikel"
 * for the same selection — the footer counted grouped ROWS, the review summed UNITS. Both now read
 * [selectedArticleCount], which is the number of units an operator is about to bill.
 */
@DisplayName("Billing — selected article count sums units, not grouped rows")
class SelectedArticleCountTest {
    // The emulator table: Espresso ×2 (ids 5, 38) and Cola ×2 (ids 6, 7) grouped, plus two singles.
    private val rows =
        listOf(
            row("Espresso", listOf(5L, 38L)).selecting(2),
            row("Cola", listOf(6L, 7L)).selecting(2),
            row("Pizza", listOf(8L)).selecting(1),
            row("Wasser", listOf(9L)).selecting(1),
        )

    @Test
    fun `grouped lines with quantity above one count every unit`() {
        assertEquals(6, rows.selectedArticleCount(), "4 rows holding 6 units is 6 Artikel, not 4")
    }

    @Test
    fun `a partially selected group counts only the selected units`() {
        val partial = listOf(row("Espresso", listOf(5L, 38L, 40L)).selecting(2), row("Cola", listOf(6L)))

        assertEquals(2, partial.selectedArticleCount())
    }

    @Test
    fun `nothing selected is zero`() {
        assertEquals(0, rows.map { it.selecting(0) }.selectedArticleCount())
    }

    @Test
    fun `the ui state exposes the same count both labels print`() {
        assertEquals(6, TableBillingUiState(orderItems = rows).selectedArticleCount)
    }

    private fun OrderItemForSelection.selecting(qty: Int) = copy(selectedQuantity = qty, totalPrice = unitPrice * Decimal.of(qty))

    private fun row(
        name: String,
        ids: List<Long>,
    ): OrderItemForSelection =
        OrderItemDto
            .OrderItemResponseDto(
                orderItemIds = ids,
                ordersId = setOf(1L),
                product =
                    ProductDto.OrderItemProductDto(
                        productId = ids.first(),
                        productNumber = ids.first().toString(),
                        productName = name,
                        en = null,
                        ch = null,
                        productType = "FOOD",
                    ),
                unitPrice = "2.50",
                finalPrice = "2.50",
                chargedUnitPrice = "2.50",
                itemStatus = "OPEN",
                vatClass = "A",
                vatRate = "19.00",
                customPrice = false,
                discount = 1.0,
                takeAway = false,
                transferred = false,
                quantity = ids.size,
            ).toSelectionRow()
}
