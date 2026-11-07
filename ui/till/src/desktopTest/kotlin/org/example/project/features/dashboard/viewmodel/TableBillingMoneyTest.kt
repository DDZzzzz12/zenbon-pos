package org.example.project.features.dashboard.viewmodel

import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.features.dashboard.presentation.viewmodel.BillingMoneyMath
import org.example.project.money.Currency
import org.example.project.money.Decimal
import org.example.project.money.Percentage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the billing arithmetic the [BillingMoneyMath] slice must replace.
 *
 * The existing billing screens compute unit prices, promotion deductions, VAT splits and
 * payment balances with `Long` minor-unit arithmetic, Double
 * multiplications and integer division that truncates sub-cent precision. These tests
 * describe the exact Decimal behavior required and fail against the current code — either
 * at the type level (because [BillingMoneyMath] does not exist yet) or at the assertion
 * level once a stub is wired.
 *
 * Every assertion is against the contract in the Task 1 brief. Task 4/5 will implement
 * [BillingMoneyMath] in the main source set; these tests will flip from RED to GREEN then.
 */
class TableBillingMoneyTest {
    // ====================================================================================
    // Step 1 — grouped-item regression: chargedUnitPrice selection
    // ====================================================================================

    /**
     * Three identical pizzas discounted to 14.37 each = 43.11 total. The billing model
     * must use [OrderItemDto.OrderItemResponseDto.chargedUnitPrice] ("14.37") as the
     * per-unit price rather than computing `finalPrice / quantity` in Integer cents
     * (4311 / 3 = 1437 — coincidentally exact for this tuple, but wrong in principle
     * because it ignores the field the server provides and would truncate cents when
     * the division is not clean).
     */
    @Test
    fun `grouped items use chargedUnitPrice not finalPrice divided by quantity`() {
        // Three pizza units at 14.37 each, delivered from the server as a grouped trio.
        val dto =
            OrderItemDto.OrderItemResponseDto(
                orderItemIds = listOf(101L, 102L, 103L),
                ordersId = setOf(1L),
                product =
                    ProductDto.OrderItemProductDto(
                        productId = 1L,
                        productNumber = "P001",
                        productName = "Pizza Margherita",
                        productType = "FOOD",
                    ),
                unitPrice = "16.90",
                finalPrice = "43.11",
                chargedUnitPrice = "14.37",
                itemStatus = "OPEN",
                vatClass = "A",
                vatRate = "0.19",
                customPrice = false,
                discount = 0.85,
                takeAway = false,
                transferred = false,
                quantity = 3,
            )

        // The billing model must parse chargedUnitPrice, not derive unit price
        // from `finalPrice / quantity` via integer minor-unit division.
        val chargedUnitPrice = Decimal.parse(dto.chargedUnitPrice)

        assertEquals(
            "14.37",
            BillingMoneyMath.selectedLineTotal(chargedUnitPrice, 1).toPlainString(),
            "selecting 1 of 3 grouped units",
        )
        assertEquals(
            "28.74",
            BillingMoneyMath.selectedLineTotal(chargedUnitPrice, 2).toPlainString(),
            "selecting 2 of 3 grouped units",
        )
        assertEquals(
            "43.11",
            BillingMoneyMath.selectedLineTotal(chargedUnitPrice, 3).toPlainString(),
            "selecting all 3 grouped units",
        )
    }

    // ====================================================================================
    // Step 2 — Decimal input parsing and promotion rounding / caps
    // ====================================================================================

    @Test
    fun `parseInput accepts sub-cent amounts`() {
        val result = BillingMoneyMath.parseInput("16.895")
        assertNotNull(result, "a valid decimal string must parse")
        assertEquals("16.895", result.toPlainString())
    }

    @Test
    fun `parseInput returns null for non-numeric text`() {
        assertNull(
            BillingMoneyMath.parseInput("not-a-number"),
            "garbage input must yield null, never a default value",
        )
    }

    @Test
    fun `parseInput returns null for empty string`() {
        assertNull(BillingMoneyMath.parseInput(""))
    }

    @Test
    fun `fixedPromotion rounds up the sub-cent deduction and caps to remaining`() {
        val remaining = Decimal.parse("10.00")
        val rawDeduction = Decimal.parse("0.005") // half-cent
        assertEquals(
            "0.01",
            BillingMoneyMath.fixedPromotion(rawDeduction, remaining).toPlainString(),
            "0.005 rounds up to 0.01 (HALF_UP, 2dp); 0.01 <= 10.00 so no cap applied",
        )
    }

    @Test
    fun `fixedPromotion caps when deduction exceeds remaining`() {
        val remaining = Decimal.parse("0.01")
        val rawDeduction = Decimal.parse("5.00")
        assertEquals(
            "0.01",
            BillingMoneyMath.fixedPromotion(rawDeduction, remaining).toPlainString(),
            "deduction must not exceed the remaining amount",
        )
    }

    @Test
    fun `percentagePromotion rounds per unit and caps to remaining`() {
        // 16.90 × (1 − 0.15) = 14.365 → HALF_UP to 2dp → 14.37 per unit.
        // Remaining is 20.00, well above the deduction, so no cap.
        val base = Decimal.parse("16.90")
        val percent = Percentage.parse("15") // 15 %
        val remaining = Decimal.parse("20.00")
        assertEquals(
            "14.37",
            BillingMoneyMath.percentagePromotion(base, percent, remaining).toPlainString(),
            "16.90 minus 15 % = 14.365, rounds to 14.37",
        )
    }

    @Test
    fun `percentagePromotion caps when the charged amount would exceed remaining`() {
        val base = Decimal.parse("16.90")
        val percent = Percentage.parse("15") // 15 %
        val remaining = Decimal.parse("10.00") // below 14.37
        assertEquals(
            "10.00",
            BillingMoneyMath.percentagePromotion(base, percent, remaining).toPlainString(),
            "promotion discount must not push the charged line above the remaining cap",
        )
    }

    // ====================================================================================
    // Step 3 — VAT split invariants and payment-balance arithmetic
    // ====================================================================================

    @Test
    fun `vatSplit at 19 percent fraction produces net plus vat equal to gross`() {
        val split =
            BillingMoneyMath.vatSplit(
                gross = Decimal.parse("119.00"),
                vatRate = Decimal.parse("0.19"),
                currency = Currency.EUR,
            )

        assertEquals("119.00", split.gross.amount.toPlainString())
        assertEquals("100.00", split.net.amount.toPlainString())
        assertEquals("19.00", split.vat.amount.toPlainString())
        assertEquals(Currency.EUR, split.gross.currency)
        assertEquals(Currency.EUR, split.net.currency)
        assertEquals(Currency.EUR, split.vat.currency)
        // The VatSplit constructor already enforces net + vat == gross;
        // this assertion double-checks in the test.
        assertEquals(
            split.gross,
            split.net + split.vat,
            "net + vat must equal gross (invariant)",
        )
    }

    @Test
    fun `vatSplit at 7 percent fraction produces correct values`() {
        val split =
            BillingMoneyMath.vatSplit(
                gross = Decimal.parse("107.00"),
                vatRate = Decimal.parse("0.07"),
                currency = Currency.EUR,
            )

        assertEquals("107.00", split.gross.amount.toPlainString())
        assertEquals("100.00", split.net.amount.toPlainString())
        assertEquals("7.00", split.vat.amount.toPlainString())
        assertEquals(split.gross, split.net + split.vat)
    }

    @Test
    fun `vatSplit at 19 percent with non-round gross produces net and vat that sum to gross`() {
        // 16.90 / 1.19 = 14.2016... → net 14.20, vat = 16.90 − 14.20 = 2.70
        val split =
            BillingMoneyMath.vatSplit(
                gross = Decimal.parse("16.90"),
                vatRate = Decimal.parse("0.19"),
                currency = Currency.EUR,
            )

        assertEquals("16.90", split.gross.amount.toPlainString())
        assertEquals("14.20", split.net.amount.toPlainString())
        assertEquals("2.70", split.vat.amount.toPlainString())
        assertEquals(split.gross, split.net + split.vat)
    }

    // ---- payment-balance arithmetic (plain Decimal, no BillingMoneyMath needed) ----------

    @Test
    fun `split payments sum exactly to total`() {
        val total = Decimal.parse("43.11")
        val cash = Decimal.parse("20.00")
        val card = Decimal.parse("23.11")
        val paid = cash + card

        assertEquals(total, paid, "cash + card must equal the bill total")
    }

    @Test
    fun `remaining balance after partial payment is correct`() {
        val total = Decimal.parse("43.11")
        val paid = Decimal.parse("20.00")
        val remaining = total - paid

        assertEquals("23.11", remaining.toPlainString())
    }

    @Test
    fun `tip is added to grand total`() {
        val subtotal = Decimal.parse("43.11")
        val tip = Decimal.parse("5.00")
        val grandTotal = subtotal + tip

        assertEquals("48.11", grandTotal.toPlainString())
    }

    @Test
    fun `voucher payment reduces remaining balance`() {
        val total = Decimal.parse("43.11")
        val voucherPayment = Decimal.parse("10.00")
        val remaining = total - voucherPayment

        assertEquals("33.11", remaining.toPlainString())
    }

    @Test
    fun `fully paid bill has zero remaining`() {
        val total = Decimal.parse("43.11")
        val payment1 = Decimal.parse("20.00")
        val payment2 = Decimal.parse("23.11")
        val paid = payment1 + payment2
        val remaining = total - paid

        assertEquals(Decimal.ZERO, remaining, "fully paid bill must have zero remaining")
    }

    @Test
    fun `overpayment produces negative remaining`() {
        val total = Decimal.parse("43.11")
        val paid = Decimal.parse("50.00")
        val remaining = total - paid

        assertEquals("-6.89", remaining.toPlainString(), "overpayment = negative remaining")
    }
}
