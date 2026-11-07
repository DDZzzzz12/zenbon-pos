package org.example.project.pdf.generator

import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.domain.dto.setup.ShopHeaderData
import org.openpdf.text.pdf.PdfReader
import org.openpdf.text.pdf.parser.PdfTextExtractor
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The reference print (pre-bill) summed the items as Double and formatted the total with
 * `"%.2f".format`, which follows the JVM locale, so the separator depended on the server. The total
 * is now a BigDecimal printed in German form (F63: "27,00 EUR") whatever the JVM locale.
 */
class GenerateReferencePrintTotalTest {
    private fun extractText(pdf: ByteArray): String {
        val reader = PdfReader(pdf)
        return try {
            (1..reader.numberOfPages).joinToString("\n") { PdfTextExtractor(reader).getTextFromPage(it) }
        } finally {
            reader.close()
        }
    }

    private fun item(
        id: Long,
        name: String,
        finalPrice: String,
    ) = OrderItemDto.OrderItemResponseDto(
        orderItemIds = listOf(id),
        ordersId = setOf(1L),
        product =
            ProductDto.OrderItemProductDto(
                productId = id,
                productNumber = "P$id",
                productName = name,
                en = null,
                ch = null,
                productType = "FOOD",
            ),
        unitPrice = finalPrice,
        finalPrice = finalPrice,
        chargedUnitPrice = finalPrice,
        itemStatus = "OPEN",
        vatClass = "A",
        vatRate = "19.00",
        customPrice = false,
        discount = 1.0,
        takeAway = false,
        transferred = false,
        quantity = 1,
    )

    @Test
    fun `the reference print total uses a German comma whatever the JVM default locale`() {
        val previous = Locale.getDefault()
        val text =
            try {
                Locale.setDefault(Locale.US)
                extractText(
                    generateReferencePrintPdfBytes(
                        shopHeader = ShopHeaderData(shopName = "Test GmbH"),
                        tableNumber = "T1",
                        employeeNumber = "M001",
                        dateTime = "01.10.2026 12:00",
                        items = listOf(item(1, "Schnitzel", "16.90"), item(2, "Wein", "10.10")),
                    ),
                )
            } finally {
                Locale.setDefault(previous)
            }

        assertTrue("27,00 EUR" in text, "total missing or dot-formatted in:\n$text")
        assertFalse("27.00 EUR" in text, text)
    }
}
