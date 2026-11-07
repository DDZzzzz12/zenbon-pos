package org.example.project.features.dashboard.state

import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.features.dashboard.presentation.state.toSelectionRow
import org.example.project.features.dashboard.presentation.state.withSurvivingSelection
import org.example.project.money.Decimal
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * When the table changes underneath the panel, the selection may keep only what still exists.
 *
 * A cashier who may not cancel keeps their selection while a manager decides (E2E-2026-09-20 #6).
 * When the manager approved, nothing reloaded the panel: the cancelled units stayed listed at their
 * old quantities and stayed **selected**, so *Artikel stornieren* could be pressed again on items
 * that no longer existed (round 2, R2-3). The panel now reloads on the table's status push, and a
 * reload must neither resurrect a cancelled unit nor throw away a selection that is still pending
 * — the push also fires when another waiter merely adds a drink.
 */
@DisplayName("Table panel refresh — the selection keeps only units that still exist")
class SurvivingSelectionTest {
    @Test
    fun `a cancelled unit drops out of the selection`() {
        // 2× Schorle (ids 10, 11), unit 11 selected and then cancelled by the approved Storno.
        val reloaded = listOf(line(ids = listOf(10L), price = "9.20"))

        val rows = reloaded.withSurvivingSelection(selectedIds = setOf(11L))

        assertEquals(0, rows.single().selectedQuantity, "the cancelled unit must not stay selected")
        assertEquals(Decimal.ZERO, rows.single().totalPrice)
    }

    @Test
    fun `a selection still pending survives an unrelated change`() {
        // The Pils (20) is selected and awaiting approval; another waiter adds a Cola (30).
        val reloaded = listOf(line(ids = listOf(20L), price = "4.50"), line(ids = listOf(30L), price = "4.50"))

        val rows = reloaded.withSurvivingSelection(selectedIds = setOf(20L))

        assertEquals(listOf(1, 0), rows.map { it.selectedQuantity })
        assertEquals(Decimal.parse("4.50"), rows.first().totalPrice)
    }

    @Test
    fun `a partly cancelled line keeps the units that are left`() {
        // 3 units selected (40, 41, 42); 41 was cancelled; the line reloads with 40 and 42.
        val reloaded = listOf(line(ids = listOf(40L, 42L), price = "5.00"))

        val row = reloaded.withSurvivingSelection(selectedIds = setOf(40L, 41L, 42L)).single()

        assertEquals(2, row.selectedQuantity)
        assertEquals(Decimal.parse("10.00"), row.totalPrice)
    }

    private fun line(
        ids: List<Long>,
        price: String,
    ) = OrderItemDto
        .OrderItemResponseDto(
            orderItemIds = ids,
            ordersId = setOf(1L),
            product =
                ProductDto.OrderItemProductDto(
                    productId = 1L,
                    productNumber = "1",
                    productName = "Product ${ids.first()}",
                    en = null,
                    ch = null,
                    productType = "DRINK",
                ),
            unitPrice = price,
            finalPrice = (Decimal.parse(price) * Decimal.of(ids.size)).toPlainString(),
            chargedUnitPrice = price,
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
