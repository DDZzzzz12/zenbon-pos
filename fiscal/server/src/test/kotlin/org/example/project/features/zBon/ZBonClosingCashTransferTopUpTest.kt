package org.example.project.features.zBon

import org.example.project.core.utils.ClosingTopUpRule
import org.example.project.features.zBon.domain.ClosingCashTransfer
import org.example.project.money.Decimal
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * The Geldtransit rule ([ClosingCashTransfer]) against the dialog's prefill ([ClosingTopUpRule]): the
 * float-preserving top-up must move nothing to the safe and leave the drawer at its float, for any
 * split of float and takings. Pure arithmetic — the end-to-end case is ZBonTipTopUpAndBankPayrollTest.
 */
@DisplayName("Z-Bon top-up: the prefill keeps the float, the Geldtransit moves nothing (F59)")
class ZBonClosingCashTransferTopUpTest {
    private data class Day(
        val float: String,
        val takings: String,
        val payouts: String,
    )

    private val shortDays =
        listOf(
            Day(float = "2.00", takings = "0.00", payouts = "5.00"),
            Day(float = "2.00", takings = "3.00", payouts = "7.00"),
            Day(float = "0.00", takings = "1.50", payouts = "9.00"),
            Day(float = "150.00", takings = "40.00", payouts = "200.00"),
        )

    @Test
    fun `the float-preserving top-up transfers nothing and the drawer ends at the float`() {
        shortDays.forEach { day ->
            val float = BigDecimal(day.float)
            val takings = BigDecimal(day.takings)
            val payouts = BigDecimal(day.payouts)
            val topUp = BigDecimal(ClosingTopUpRule.floatPreservingTopUp(Decimal.parse(day.payouts), Decimal.parse(day.takings)).toPlainString())
            val shortfall = ClosingTopUpRule.shortfall(Decimal.parse(day.payouts), Decimal.parse(day.float), Decimal.parse(day.takings))
            check(shortfall != null) { "fixture: $day must be short" }
            check(topUp >= BigDecimal(shortfall.toPlainString())) { "the prefill always covers the shortfall: $day" }

            val drawerBeforeTransfer = float + takings + topUp - payouts
            val transfer = ClosingCashTransfer.amount(ClosingCashTransfer.Inputs(takings, payouts, topUp, drawerBeforeTransfer))

            assertEquals(0, transfer.signum(), "$day: nothing goes to the safe")
            assertEquals(0, float.compareTo(drawerBeforeTransfer - transfer), "$day: the drawer ends at its float")
        }
    }

    /** Negative control: the bare shortfall empties the drawer — which is why it is not the prefill. */
    @Test
    fun `negative control - the bare shortfall leaves the drawer empty`() {
        val day = shortDays[1]
        val topUp = BigDecimal(ClosingTopUpRule.shortfall(Decimal.parse(day.payouts), Decimal.parse(day.float), Decimal.parse(day.takings))!!.toPlainString())
        val drawer = BigDecimal(day.float) + BigDecimal(day.takings) + topUp - BigDecimal(day.payouts)
        val transfer = ClosingCashTransfer.amount(ClosingCashTransfer.Inputs(BigDecimal(day.takings), BigDecimal(day.payouts), topUp, drawer))
        assertEquals(0, transfer.signum())
        assertEquals(0, BigDecimal.ZERO.compareTo(drawer - transfer))
    }

    /** A top-up above the prefill travels straight back to the safe: the drawer never ends above its float. */
    @Test
    fun `a top-up above the prefill goes back to the safe`() {
        val float = BigDecimal("2.00")
        val takings = BigDecimal("3.00")
        val payouts = BigDecimal("7.00")
        val topUp = BigDecimal("6.50") // prefill is 4.00
        val drawer = float + takings + topUp - payouts
        val transfer = ClosingCashTransfer.amount(ClosingCashTransfer.Inputs(takings, payouts, topUp, drawer))
        assertEquals(0, BigDecimal("2.50").compareTo(transfer))
        assertEquals(0, float.compareTo(drawer - transfer))
    }
}
