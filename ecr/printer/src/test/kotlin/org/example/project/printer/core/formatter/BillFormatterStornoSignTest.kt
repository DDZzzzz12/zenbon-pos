package org.example.project.printer.core.formatter

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.domain.dto.promotion.PromotionDto
import org.example.project.core.domain.dto.setup.ShopHeaderData
import org.example.project.core.domain.enums.bill.BillKind
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.bill.StornoReason
import org.example.project.core.domain.enums.setup.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A printed Beleg must add up to its own QR. A Storno's DTO carries its totals negated but lists the
 * original's (now REFUNDED) payments with positive amounts and no promotion rows, so the paper read
 * "SUMME -13.50 / BAR 11.50 / GESAMT -11.50" against a QR of `-11.50:Bar` (emu10 FAIL 4).
 */
class BillFormatterStornoSignTest {
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
        tips: String = "0.00",
        promotionAmount: String = "0.00",
        promotions: List<PromotionDto.BillPromotionResponseDto> = emptyList(),
        payments: List<PaymentDto.PaymentResponse>,
        storno: Boolean = false,
        stornoReason: StornoReason? = null,
        orderItems: List<OrderItemDto.OrderItemResponseDto> = emptyList(),
    ) = BillDto.BillResponseDto(
        billId = 1,
        employeeId = "emp-1",
        employeeNumber = "K001",
        tipRecipient = "EMPLOYEE",
        tableSessionId = 1,
        tableNumber = "T1",
        billNumber = if (storno) "RE-260930-21" else "RE-260930-20",
        billAmount = billAmount,
        promotionAmount = promotionAmount,
        promotions = promotions,
        paidAmount = paidAmount,
        tips = tips,
        startTime = "2026-09-30T12:00:00",
        endTime = "2026-09-30T12:05:00",
        deletedBillId = if (storno) "RE-260930-20" else null,
        orderItems = orderItems,
        payments = payments,
        relations =
            if (storno) {
                BillDto.BillRelationsDto(
                    kind = BillKind.STORNO,
                    reversesBill = BillDto.BillLinkDto(20, "RE-260930-20"),
                    stornoReason = stornoReason,
                )
            } else {
                BillDto.BillRelationsDto()
            },
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

    private fun print(bill: BillDto.BillResponseDto) =
        runBlocking { BillFormatter().format(bill, ShopHeaderData(shopName = "Test"), Language.GERMAN) }.printedLines()

    private fun List<String>.lineWith(label: String): String = assertNotNull(firstOrNull { label in it }, "no '$label' line in:\n${joinToString("\n")}")

    /** The line's printed amount — the last "<n,nn> EUR" on it (font-switch bytes may trail the text). */
    private fun String.amount(): String = assertNotNull(Regex("""(-?[\d.]*\d,\d{2}) EUR""").findAll(this).lastOrNull(), "no amount in '$this'").groupValues[1]

    @Test
    fun `a sale prints as before - positive tender, the promotion subtracted`() {
        val lines = print(bill("13.50", "11.50", promotionAmount = "2.00", promotions = listOf(happyHour), payments = listOf(payment("EC-KARTE", "11.50"))))

        assertEquals("11,50", lines.lineWith("EC-KARTE").amount())
        assertEquals("-2,00", lines.lineWith("Aktion").amount())
        assertEquals("13,50", lines.lineWith("SUMME").amount())
        assertEquals("11,50", lines.lineWith("GESAMT").amount())
    }

    @Test
    fun `a Storno prints its tender negative and adds its promotion back, so the paper sums to GESAMT`() {
        val lines =
            print(
                bill(
                    "-13.50",
                    "-11.50",
                    promotionAmount = "-2.00",
                    payments = listOf(payment("BAR", "11.50", PaymentStatus.REFUNDED)),
                    storno = true,
                ),
            )

        assertEquals("-11,50", lines.lineWith("BAR").amount(), "the refund is paid out: QR says -11.50:Bar")
        val promotion = lines.lineWith("Aktion")
        assertEquals("2,00", promotion.amount(), "the promotion is reversed with the sale: -13,50 + 2,00 = -11,50")
        assertEquals("-13,50", lines.lineWith("SUMME").amount())
        assertEquals("-11,50", lines.lineWith("GESAMT").amount())
    }

    @Test
    fun `a Storno of a tipped sale prints the tip and the tender negative`() {
        val lines =
            print(
                bill(
                    "-23.00",
                    "-25.00",
                    tips = "-2.00",
                    payments = listOf(payment("EC-KARTE", "25.00", PaymentStatus.REFUNDED)),
                    storno = true,
                ),
            )

        assertEquals("-2,00", lines.lineWith("TRINKGELD").amount(), "-23,00 + -2,00 = -25,00")
        assertEquals("-25,00", lines.lineWith("EC-KARTE").amount())
        assertEquals("-25,00", lines.lineWith("GESAMT").amount())
        assertTrue(lines.none { "Aktion" in it }, "no promotion, no Aktion line")
    }

    @Test
    fun `a Storno prints its items negative under a STORNO heading naming the original`() {
        val lines =
            print(
                bill(
                    "-12.50",
                    "-10.50",
                    promotionAmount = "-2.00",
                    payments = listOf(payment("EC-KARTE", "10.50", PaymentStatus.REFUNDED)),
                    storno = true,
                    orderItems = listOf(wine("10.00"), wine("2.50").copy(product = wine().product.copy(productName = "Espresso"))),
                ),
            )

        assertEquals("-10,00", lines.lineWith("Wein").amount(), "items, SUMME, payments and GESAMT agree in sign")
        assertEquals("-2,50", lines.lineWith("Espresso").amount())
        // Bold-toggle bytes may lead the line; the heading is what it ends with.
        val heading = lines.indexOfFirst { it.trimEnd().endsWith("STORNO") }
        assertTrue(heading >= 0, "a STORNO heading:\n${lines.joinToString("\n")}")
        assertTrue(lines.any { "zu Beleg RE-260930-20" in it }, "names the Beleg it reverses")
        assertTrue(heading < lines.indexOfFirst { "Wein" in it }, "the heading stands above the items")
    }

    @Test
    fun `a Zahlartwechsel Storno is headed as one`() {
        val lines =
            print(
                bill(
                    "-10.00",
                    "-10.00",
                    payments = listOf(payment("BAR", "10.00", PaymentStatus.REFUNDED)),
                    storno = true,
                    stornoReason = StornoReason.ZAHLARTWECHSEL,
                    orderItems = listOf(wine()),
                ),
            )

        assertTrue(lines.any { it.trimEnd().endsWith("STORNO (Zahlartwechsel)") }, lines.joinToString("\n"))
        assertTrue(lines.any { "zu Beleg RE-260930-20" in it })
    }

    @Test
    fun `a sale's items print positive and carry no STORNO heading`() {
        val lines = print(bill("10.00", "10.00", payments = listOf(payment("BAR", "10.00")), orderItems = listOf(wine())))

        assertEquals("10,00", lines.lineWith("Wein").amount())
        assertTrue(lines.none { "STORNO" in it || "zu Beleg" in it }, lines.joinToString("\n"))
    }

    /**
     * QA emu15 NOTE-6: the old 4-way quarter split gave the promotion name only
     * MAX_CHARS_SMALL/4 (12) characters including the "Aktion: " prefix, so "Rabatt Wein A" printed
     * as "Aktion: Raba". The full name must survive on the paper.
     */
    @Test
    fun `a promotion name longer than a quarter line prints in full`() {
        val promo =
            happyHour.copy(promotionName = "Rabatt Wein A", deductedAmount = "8.00")
        val lines =
            print(
                bill(
                    "30.00",
                    "20.00",
                    promotionAmount = "8.00",
                    promotions = listOf(promo),
                    payments = listOf(payment("EC-KARTE", "20.00")),
                ),
            )

        val line = lines.lineWith("Rabatt Wein A")
        assertTrue("Aktion: Rabatt Wein A" in line, "the full name must not be cut off: '$line'")
        assertEquals("-8,00", line.amount())
        assertFalse(lines.any { "Aktion: Raba" == it.trim() || it.trim().endsWith("Aktion: Raba") }, "the old truncation must be gone")
    }

    @Test
    fun `two promotions on one bill each print their own full name`() {
        val promoA = happyHour.copy(promotionName = "Rabatt Wein A", deductedAmount = "8.00")
        val promoB = happyHour.copy(id = 2, promotionId = 2, promotionName = "Rabatt Wein B", deductedAmount = "2.00")
        val lines =
            print(
                bill(
                    "30.00",
                    "20.00",
                    promotionAmount = "10.00",
                    promotions = listOf(promoA, promoB),
                    payments = listOf(payment("EC-KARTE", "20.00")),
                ),
            )

        val promoLines = lines.filter { "Aktion:" in it }
        assertEquals(2, promoLines.size, "one line per promotion: ${lines.joinToString("\n")}")
        assertTrue(promoLines.any { "Aktion: Rabatt Wein A" in it && it.amount() == "-8,00" })
        assertTrue(promoLines.any { "Aktion: Rabatt Wein B" in it && it.amount() == "-2,00" })
    }

    @Test
    fun `a promotion name too long to share its line with the amount wraps instead of truncating`() {
        val longName = "Stammgastrabatt fuer treue Gaeste des Hauses QA Bistro Muenchen"
        val promo = happyHour.copy(promotionName = longName, deductedAmount = "3.00")
        val lines =
            print(
                bill(
                    "30.00",
                    "27.00",
                    promotionAmount = "3.00",
                    promotions = listOf(promo),
                    payments = listOf(payment("EC-KARTE", "27.00")),
                ),
            )

        // Every word of the label must survive intact somewhere on the paper — wrapping breaks only
        // at spaces, never mid-word, unlike the old quarter-column truncation.
        ("Aktion: $longName").split(" ").forEach { word ->
            assertTrue(lines.any { word in it }, "word '$word' missing or split: ${lines.joinToString("\n")}")
        }
        val amountLine = assertNotNull(lines.lastOrNull { it.trim().endsWith("3,00 EUR") }, lines.joinToString("\n"))
        assertTrue(amountLine.length <= BaseDocumentFormatter.MAX_CHARS_SMALL + 2, "amount line still fits the paper: '$amountLine'")
    }
}
