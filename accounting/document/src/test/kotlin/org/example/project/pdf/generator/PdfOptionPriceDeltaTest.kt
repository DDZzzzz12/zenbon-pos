package org.example.project.pdf.generator

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.domain.dto.setup.ShopHeaderData
import org.openpdf.text.pdf.PdfReader
import org.openpdf.text.pdf.parser.PdfTextExtractor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * emu21 NOTE-y: the PDF receipt and PDF reference print listed choice options without their price
 * deltas, while the paper receipt shows „- QA21-Groß (1,50)". Both now print the paper's label.
 */
class PdfOptionPriceDeltaTest {
    private fun extractText(pdf: ByteArray): String {
        val reader = PdfReader(pdf)
        return try {
            (1..reader.numberOfPages).joinToString("\n") { PdfTextExtractor(reader).getTextFromPage(it) }
        } finally {
            reader.close()
        }
    }

    private val box =
        OrderItemDto.OrderItemResponseDto(
            orderItemIds = listOf(1L),
            ordersId = setOf(1L),
            product =
                ProductDto.OrderItemProductDto(
                    productId = 1L,
                    productNumber = "NB",
                    productName = "Nudelbox",
                    en = null,
                    ch = null,
                    productType = "FOOD",
                ),
            unitPrice = "8.00",
            finalPrice = "10.50",
            chargedUnitPrice = "10.50",
            itemStatus = "OPEN",
            vatClass = "B",
            vatRate = "7.00",
            customPrice = false,
            discount = 1.0,
            takeAway = false,
            transferred = false,
            quantity = 1,
            extras =
                listOf(
                    OrderItemDto.SelectedOptionDto(name = "Gross", priceDelta = "1.5000"),
                    OrderItemDto.SelectedOptionDto(name = "Kaese", priceDelta = "1.0000"),
                    OrderItemDto.SelectedOptionDto(name = "Ohne Zwiebeln", priceDelta = "0.0000"),
                ),
        )

    private fun assertDeltas(text: String) {
        assertTrue("Gross (1,50)" in text.replace(Regex(" +"), " "), text)
        assertTrue("Kaese (1,00)" in text.replace(Regex(" +"), " "), text)
        assertFalse("1.5000" in text, "no raw DB scale: $text")
        assertFalse(Regex("Ohne Zwiebeln\\s*\\(").containsMatchIn(text), "a free option carries no price: $text")
    }

    @Test
    fun `the PDF receipt prints each option's price delta like the paper`() {
        val bill =
            BillDto.BillResponseDto(
                billId = 1L,
                employeeId = "emp-1",
                employeeNumber = "K001",
                tipRecipient = "EMPLOYEE",
                tableSessionId = 1L,
                tableNumber = "T1",
                billNumber = "RE-261003-17",
                billAmount = "10.50",
                paidAmount = "10.50",
                tips = "0.00",
                startTime = "2026-10-03T12:00:00",
                endTime = "2026-10-03T12:05:00",
                orderItems = listOf(box),
                payments = emptyList(),
            )
        assertDeltas(extractText(generateBillReceiptPdfBytes(bill, ShopHeaderData(shopName = "Test GmbH"))))
    }

    @Test
    fun `the PDF reference print prints each option's price delta like the paper`() {
        val pdf =
            generateReferencePrintPdfBytes(
                shopHeader = ShopHeaderData(shopName = "Test GmbH"),
                tableNumber = "T1",
                employeeNumber = "K001",
                dateTime = "03.10.2026 12:00",
                items = listOf(box),
            )
        assertDeltas(extractText(pdf))
    }
}
