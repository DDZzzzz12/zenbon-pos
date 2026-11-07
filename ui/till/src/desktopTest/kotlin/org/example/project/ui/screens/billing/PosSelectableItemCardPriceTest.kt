package org.example.project.ui.screens.billing

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.features.dashboard.presentation.state.OrderItemForSelection
import org.example.project.money.Decimal
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * 2026-09-18 e2e finding #12: the "Einzelpreis" / "Summe" headers had nothing under them for
 * unselected rows, and nothing at all for rows with a quantity stepper.
 */
@OptIn(ExperimentalTestApi::class)
class PosSelectableItemCardPriceTest {
    @BeforeTest
    fun translations() = TranslationRegistry.initialize(defaultTranslations())

    private fun abendbuffet(
        quantity: Int,
        selected: Int,
    ): OrderItemForSelection {
        val unit = Decimal.parse("26.80")
        return OrderItemForSelection(
            item =
                OrderItemDto.OrderItemResponseDto(
                    orderItemIds = (1L..quantity.toLong()).toList(),
                    ordersId = setOf(1L),
                    product =
                        ProductDto.OrderItemProductDto(
                            productId = 1L,
                            productNumber = "K002",
                            productName = "Abendbuffet",
                            productType = "FOOD",
                        ),
                    unitPrice = "26.80",
                    finalPrice = (unit * Decimal.of(quantity)).toPlainString(),
                    chargedUnitPrice = "26.80",
                    itemStatus = "OPEN",
                    vatClass = "A",
                    vatRate = "0.19",
                    customPrice = false,
                    discount = 0.0,
                    takeAway = false,
                    transferred = false,
                    quantity = quantity,
                ),
            maxQuantity = quantity,
            selectedQuantity = selected,
            unitPrice = unit,
            totalPrice = unit * Decimal.of(selected),
        )
    }

    @Test
    fun `an unselected line with a stepper still shows its unit price and line sum`() =
        runComposeUiTest {
            setContent { PosSelectableItemCard(item = abendbuffet(quantity = 2, selected = 0), onQuantityChange = {}) }
            onNodeWithText("26,80", substring = true).assertIsDisplayed() // unit
            onNodeWithText("53,60", substring = true).assertIsDisplayed() // line total for 2
        }

    @Test
    fun `a partly selected line shows the selected sum`() =
        runComposeUiTest {
            setContent { PosSelectableItemCard(item = abendbuffet(quantity = 3, selected = 2), onQuantityChange = {}) }
            onNodeWithText("26,80", substring = true).assertIsDisplayed()
            onNodeWithText("53,60", substring = true).assertIsDisplayed() // 2 × 26,80
        }

    @Test
    fun `an unselected single line shows its price`() =
        runComposeUiTest {
            setContent { PosSelectableItemCard(item = abendbuffet(quantity = 1, selected = 0), onQuantityChange = {}) }
            onNodeWithText("26,80", substring = true).assertIsDisplayed()
        }
}
