package org.example.project.printer.core.formatter

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.domain.dto.setup.ShopHeaderData
import org.example.project.core.domain.enums.setup.Language
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * emu18 NOTE-h: an extra's price delta arrives at the DB scale (`4.0000`) and was printed raw —
 * `- Gebratene Reis  (4.0000)` — while every other amount on the paper reads `20.80`. Every
 * document (receipt, Storno, kitchen ticket, reference print) prints items via the shared
 * `printOrderItem`, so the reference print stands for all of them.
 */
class ExtraPriceDeltaPrintTest {
    private fun option(
        name: String,
        priceDelta: String,
    ) = OrderItemDto.SelectedOptionDto(name = name, priceDelta = priceDelta)

    private fun chicken(vararg extras: OrderItemDto.SelectedOptionDto) =
        OrderItemDto.OrderItemResponseDto(
            orderItemIds = listOf(1L),
            ordersId = setOf(1L),
            product =
                ProductDto.OrderItemProductDto(
                    productId = 1L,
                    productNumber = "12",
                    productName = "Huhn vom Lande",
                    en = null,
                    ch = null,
                    productType = "FOOD",
                ),
            unitPrice = "20.80",
            finalPrice = "20.80",
            chargedUnitPrice = "20.80",
            itemStatus = "OPEN",
            vatClass = "B",
            vatRate = "7.00",
            customPrice = false,
            discount = 1.0,
            takeAway = false,
            transferred = false,
            quantity = 1,
            extras = extras.toList(),
        )

    private fun referencePrint(item: OrderItemDto.OrderItemResponseDto): List<String> =
        runBlocking {
            ReferencePrintFormatter().format(
                shopHeader = ShopHeaderData(shopName = "Test"),
                tableNumber = "3",
                employeeNumber = "K001",
                dateTime = "02.10.2026 12:00",
                items = listOf(item),
                language = Language.GERMAN,
            )
        }.printedLines()

    private fun List<String>.lineWith(label: String): String = assertNotNull(firstOrNull { label in it }, "no '$label' line in:\n${joinToString("\n")}")

    @Test
    fun `an extra's price delta prints with two decimals, not the DB scale`() {
        val lines = referencePrint(chicken(option("Gebratene Reis", "4.0000")))

        val extra = lines.lineWith("Gebratene Reis")
        assertTrue("(4,00)" in extra, "two decimals like the rest of the receipt: '$extra'")
        assertTrue("4.0000" !in extra, "no raw DB scale: '$extra'")
    }

    @Test
    fun `a negative delta keeps its sign and a zero delta prints no price`() {
        val lines = referencePrint(chicken(option("Ohne Sauce", "-0.5000"), option("Extra scharf", "0.0000")))

        assertTrue("(-0,50)" in lines.lineWith("Ohne Sauce"))
        assertTrue("(" !in lines.lineWith("Extra scharf"), "a free option carries no price")
    }
}
