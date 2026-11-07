package org.example.project.pdf.generator

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.domain.dto.promotion.PromotionDto
import org.example.project.core.domain.dto.setup.ShopHeaderData
import org.example.project.core.domain.enums.bill.BillKind
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.bill.StornoReason
import org.openpdf.text.pdf.PdfReader
import org.openpdf.text.pdf.parser.PdfTextExtractor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The PDF Beleg must add up to its own QR, like the ESC/POS one. A Storno's DTO carries its totals
 * negated but lists the original's REFUNDED payments with positive amounts and no promotion rows.
 */
class PdfReceiptStornoSignTest {
    private fun extractText(pdf: ByteArray): String {
        val reader = PdfReader(pdf)
        return try {
            (1..reader.numberOfPages).joinToString("\n") { PdfTextExtractor(reader).getTextFromPage(it) }
        } finally {
            reader.close()
        }
    }

    private fun payment(
        name: String,
        amount: String,
        status: PaymentStatus = PaymentStatus.COMPLETED,
    ) = PaymentDto.PaymentResponse(
        id = 1,
        amount = amount,
        paymentTypeId = 1,
        paymentTypeName = name,
        billId = 1,
        createdAt = "2026-09-30T12:00:00",
        paymentStatus = status,
    )

    private fun bill(
        billAmount: String,
        paidAmount: String,
        promotionAmount: String,
        promotions: List<PromotionDto.BillPromotionResponseDto>,
        payments: List<PaymentDto.PaymentResponse>,
        storno: Boolean,
        relations: BillDto.BillRelationsDto = if (storno) BillDto.BillRelationsDto(kind = BillKind.STORNO) else BillDto.BillRelationsDto(),
        orderItems: List<OrderItemDto.OrderItemResponseDto> = emptyList(),
    ) = BillDto.BillResponseDto(
        billId = 1,
        employeeId = "emp-1",
        employeeNumber = "K001",
        tipRecipient = "EMPLOYEE",
        tableSessionId = 1,
        tableNumber = "T1",
        billNumber = "RE-1",
        billAmount = billAmount,
        promotionAmount = promotionAmount,
        promotions = promotions,
        paidAmount = paidAmount,
        tips = "0.00",
        startTime = "2026-09-30T12:00:00",
        endTime = "2026-09-30T12:05:00",
        deletedBillId = if (storno) "RE-0" else null,
        orderItems = orderItems,
        payments = payments,
        relations = relations,
    )

    private fun wine(finalPrice: String = "10.00") =
        OrderItemDto.OrderItemResponseDto(
            orderItemIds = listOf(7L),
            ordersId = setOf(1L),
            product =
                ProductDto.OrderItemProductDto(
                    productId = 7L,
                    productNumber = "W10",
                    productName = "Wein",
                    en = null,
                    ch = null,
                    productType = "DRINKS",
                ),
            unitPrice = finalPrice,
            finalPrice = finalPrice,
            chargedUnitPrice = finalPrice,
            itemStatus = "PAID",
            vatClass = "A",
            vatRate = "19.00",
            customPrice = false,
            discount = 1.0,
            takeAway = false,
            transferred = false,
            quantity = 1,
        )

    private val happyHour =
        PromotionDto.BillPromotionResponseDto(
            id = 1,
            promotionId = 1,
            promotionName = "Happy Hour Wein",
            orderItemId = null,
            deductedAmount = "2.00",
            appliedBy = "K001",
            appliedAt = "2026-09-30T12:05:00",
        )

    private val shop = ShopHeaderData(shopName = "Test GmbH")

    @Test
    fun `a sale prints its tender positive and its promotion subtracted`() {
        val text =
            extractText(
                generateBillReceiptPdfBytes(bill("13.50", "11.50", "2.00", listOf(happyHour), listOf(payment("EC-KARTE", "11.50")), storno = false), shop),
            )

        assertTrue("EC-KARTE: 11,50" in text, text)
        assertTrue(Regex("""Aktion: Happy Hour Wein\s*-2,00""").containsMatchIn(text), text)
    }

    @Test
    fun `a Storno prints its tender negative and its promotion added back`() {
        val text =
            extractText(
                generateBillReceiptPdfBytes(
                    bill("-13.50", "-11.50", "-2.00", emptyList(), listOf(payment("BAR", "11.50", PaymentStatus.REFUNDED)), storno = true),
                    shop,
                ),
            )

        assertTrue("BAR: -11,50" in text, "the refund is paid out, as the QR's -11.50:Bar says:\n$text")
        assertFalse("BAR: 11,50" in text, text)
        assertTrue(Regex("""Aktion\S*\s+2,00""").containsMatchIn(text), "-13,50 + 2,00 = -11,50 — the promotion line must be there:\n$text")
    }

    @Test
    fun `a Storno prints its items negative under a STORNO heading naming the original`() {
        val text =
            extractText(
                generateBillReceiptPdfBytes(
                    bill(
                        "-10.00",
                        "-10.00",
                        "0.00",
                        emptyList(),
                        listOf(payment("BAR", "10.00", PaymentStatus.REFUNDED)),
                        storno = true,
                        orderItems = listOf(wine()),
                    ),
                    shop,
                ),
            )

        assertTrue(Regex("""1x Wein\s*-10,00""").containsMatchIn(text), "the item agrees with Summe -10,00:\n$text")
        assertTrue(Regex("""(?m)^\s*STORNO\s*$""").containsMatchIn(text), text)
        assertTrue("zu Beleg RE-0" in text, "falls back to deletedBillId when no link is loaded:\n$text")
        assertFalse("RECHNUNG" in text, text)
    }

    @Test
    fun `a Zahlartwechsel Storno is headed as one and names its original from the relation`() {
        val text =
            extractText(
                generateBillReceiptPdfBytes(
                    bill(
                        "-10.00",
                        "-10.00",
                        "0.00",
                        emptyList(),
                        listOf(payment("BAR", "10.00", PaymentStatus.REFUNDED)),
                        storno = true,
                        relations =
                            BillDto.BillRelationsDto(
                                kind = BillKind.STORNO,
                                reversesBill = BillDto.BillLinkDto(5, "RE-260930-5"),
                                stornoReason = StornoReason.ZAHLARTWECHSEL,
                            ),
                        orderItems = listOf(wine()),
                    ),
                    shop,
                ),
            )

        assertTrue("STORNO (Zahlartwechsel)" in text, text)
        assertTrue("zu Beleg RE-260930-5" in text, text)
    }

    @Test
    fun `a sale keeps its RECHNUNG heading and positive items`() {
        val text =
            extractText(
                generateBillReceiptPdfBytes(
                    bill("10.00", "10.00", "0.00", emptyList(), listOf(payment("BAR", "10.00")), storno = false, orderItems = listOf(wine())),
                    shop,
                ),
            )

        assertTrue("RECHNUNG" in text, text)
        assertTrue(Regex("""1x Wein\s*10,00""").containsMatchIn(text), text)
        assertFalse("STORNO" in text, text)
    }
}
