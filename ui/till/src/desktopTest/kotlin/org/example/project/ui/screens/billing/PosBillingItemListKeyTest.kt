package org.example.project.ui.screens.billing

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.features.dashboard.presentation.state.OrderItemForSelection
import org.example.project.money.Decimal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Found while auditing LazyColumn keys for E-1: the billing list keyed each line by
 * `orderItemIds.hashCode()`, and `List.hashCode()` collides for disjoint id groups —
 * `[100, 133]` and `[101, 102]` both hash to 4194 — which is a duplicate key and a crash.
 */
@OptIn(ExperimentalTestApi::class)
class PosBillingItemListKeyTest {
    @BeforeTest
    fun translations() = TranslationRegistry.initialize(defaultTranslations())

    private fun line(
        ids: List<Long>,
        name: String,
    ): OrderItemForSelection {
        val unit = Decimal.parse("5.00")
        return OrderItemForSelection(
            item =
                OrderItemDto.OrderItemResponseDto(
                    orderItemIds = ids,
                    ordersId = setOf(1L),
                    product =
                        ProductDto.OrderItemProductDto(
                            productId = ids.first(),
                            productNumber = "P${ids.first()}",
                            productName = name,
                            productType = "FOOD",
                        ),
                    unitPrice = "5.00",
                    finalPrice = "10.00",
                    chargedUnitPrice = "5.00",
                    itemStatus = "OPEN",
                    vatClass = "A",
                    vatRate = "0.19",
                    customPrice = false,
                    discount = 0.0,
                    takeAway = false,
                    transferred = false,
                    quantity = ids.size,
                ),
            maxQuantity = ids.size,
            unitPrice = unit,
            totalPrice = Decimal.ZERO,
        )
    }

    @Test
    fun `id groups whose list hashCodes collide render both lines instead of crashing`() {
        check(listOf(100L, 133L).hashCode() == listOf(101L, 102L).hashCode()) { "fixture no longer collides" }
        runComposeUiTest {
            setContent {
                PosBillingItemList(
                    orderItems = listOf(line(listOf(100L, 133L), "Apfelschorle"), line(listOf(101L, 102L), "Kaffee")),
                    onItemQuantityChange = { _, _ -> },
                    onSelectAll = {},
                    onClearSelection = {},
                )
            }
            assertEquals(1, onAllNodesWithText("Apfelschorle", substring = true).fetchSemanticsNodes().size)
            assertEquals(1, onAllNodesWithText("Kaffee", substring = true).fetchSemanticsNodes().size)
        }
    }
}
