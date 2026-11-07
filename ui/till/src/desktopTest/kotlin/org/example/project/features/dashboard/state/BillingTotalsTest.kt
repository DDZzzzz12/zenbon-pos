package org.example.project.features.dashboard.state

import org.example.project.features.dashboard.presentation.state.BillingTotals
import org.example.project.money.Decimal
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the payment screen may call "Gesamt".
 *
 * The header bound `totalAmount` to `billTotal`, the gross subtotal *before* promotions, while
 * `remainingAmount` was derived from the payable amount. On a bill of 30,20 € with a 16,50 €
 * free-item promotion it therefore read
 * `Bezahlt: 0,00 € · Verbleibend: 13,70 € · Gesamt: 30,20 €` — and nothing on that screen stated
 * the 13,70 € the customer actually owed (E2E-2026-09-20 #9). Without a promotion or discount the
 * three agree, so the inconsistency appeared exactly when it mattered.
 *
 * The invariant is the contract: **Bezahlt + Verbleibend == Gesamt + Trinkgeld**, at every stage of
 * tendering — and "Gesamt" is the bill, never the amount tendered (F65).
 */
@DisplayName("Billing totals — paid + remaining always equals the total shown plus the tip")
class BillingTotalsTest {
    @Test
    fun `a promotion reduces the total the customer owes`() {
        val totals = BillingTotals.of(subtotal = d("30.20"), promotionDeduction = d("16.50"), paidAmount = Decimal.ZERO)

        assertEquals(d("13.70"), totals.payableTotal)
        assertEquals(d("13.70"), totals.remainingAmount)
        assertEquals(Decimal.ZERO, totals.tipAmount)
    }

    @Test
    fun `paid plus remaining equals the payable total part way through tendering`() {
        val totals = BillingTotals.of(subtotal = d("30.20"), promotionDeduction = d("16.50"), paidAmount = d("5.00"))

        assertEquals(d("8.70"), totals.remainingAmount)
        assertEquals(totals.payableTotal, totals.paidAmount + totals.remainingAmount)
    }

    @Test
    fun `an overpayment becomes a tip and the invariant still holds`() {
        // 13,90 € tendered on a 13,70 € bill.
        val totals = BillingTotals.of(subtotal = d("30.20"), promotionDeduction = d("16.50"), paidAmount = d("13.90"))

        assertEquals(d("0.20"), totals.tipAmount)
        assertEquals(Decimal.ZERO, totals.remainingAmount, "an overpaid bill owes nothing")
        assertEquals(d("13.70"), totals.payableTotal, "F65: Gesamt stays the bill, the tip is shown on its own")
        assertEquals(
            totals.payableTotal + totals.tipAmount,
            totals.paidAmount + totals.remainingAmount,
            "paid + remaining == total + tip, so the header still adds up",
        )
    }

    @Test
    fun `F65 - eleven euros on a ten fifty bill is a ten fifty total and a fifty cent tip`() {
        val totals = BillingTotals.of(subtotal = d("10.50"), promotionDeduction = Decimal.ZERO, paidAmount = d("11.00"))

        assertEquals(d("10.50"), totals.payableTotal, "Gesamt is the bill, not the amount paid")
        assertEquals(d("0.50"), totals.tipAmount)
        assertEquals(Decimal.ZERO, totals.remainingAmount)
    }

    @Test
    fun `the remaining amount is never negative, however much is overpaid`() {
        listOf("10.50", "10.51", "11.00", "100.00").forEach { paid ->
            val totals = BillingTotals.of(subtotal = d("10.50"), promotionDeduction = Decimal.ZERO, paidAmount = d(paid))
            assertTrue(totals.remainingAmount >= Decimal.ZERO, "paid $paid")
        }
    }

    @Test
    fun `without a promotion the payable total is the gross subtotal`() {
        val totals = BillingTotals.of(subtotal = d("9.20"), promotionDeduction = Decimal.ZERO, paidAmount = Decimal.ZERO)

        assertEquals(d("9.20"), totals.payableTotal)
        assertEquals(d("9.20"), totals.grossTotal)
    }

    @Test
    fun `a promotion larger than the bill never makes the customer owe a negative amount`() {
        val totals = BillingTotals.of(subtotal = d("10.00"), promotionDeduction = d("25.00"), paidAmount = Decimal.ZERO)

        assertEquals(Decimal.ZERO, totals.payableTotal)
        assertEquals(Decimal.ZERO, totals.remainingAmount)
    }

    private fun d(v: String) = Decimal.parse(v)
}
