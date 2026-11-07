package org.example.project.core.utils

import org.example.project.money.Decimal
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RebookTenderTest {
    private fun d(s: String) = Decimal.parse(s)

    private val card = 2L
    private val cash = 1L
    private val voucher = 7L
    private val original = RebookTender(payments = listOf(card to d("23.00")), tip = d("0.00"))

    @Test
    fun `a corrected voucher number is a change`() {
        val wrong = RebookTender(listOf(voucher to d("23.00")), d("0"), vouchers = listOf("G2026-0001"))
        val right = RebookTender(listOf(voucher to d("23.00")), d("0"), vouchers = listOf("G2026-0011"))
        assertTrue(right.changesFrom(wrong))
    }

    @Test
    fun `adding a tip on the same card is a change`() {
        val new = RebookTender(listOf(card to d("25.00")), d("2.00"))
        assertTrue(new.changesFrom(original))
        assertTrue(new.balances(billTotal = d("23.00")))
    }

    @Test
    fun `same tender and tip is no change`() {
        assertFalse(RebookTender(listOf(card to d("23.0")), d("0")).changesFrom(original))
    }

    @Test
    fun `split order does not matter`() {
        val a = RebookTender(listOf(cash to d("10.00"), card to d("13.00")), d("0"))
        val b = RebookTender(listOf(card to d("13.00"), cash to d("10.00")), d("0"))
        assertFalse(a.changesFrom(b))
    }

    @Test
    fun `payments must equal total plus tip`() {
        assertFalse(RebookTender(listOf(card to d("24.00")), d("2.00")).balances(d("23.00")))
    }

    @Test
    fun `negative tip or non-positive payment is invalid`() {
        assertFalse(RebookTender(listOf(card to d("21.00")), d("-2.00")).isWellFormed())
        assertFalse(RebookTender(listOf(card to d("0.00")), d("0")).isWellFormed())
        assertFalse(RebookTender(emptyList(), d("0")).isWellFormed())
        assertTrue(RebookTender(listOf(card to d("25.00")), d("2.00")).isWellFormed())
    }

    @Test
    fun `more than two decimals is invalid`() {
        assertFalse(RebookTender(listOf(card to d("25.001")), d("2.00")).isWellFormed())
    }

    @Test
    fun `same amount on a different payment type is a change`() {
        val paidByCash = RebookTender(listOf(cash to d("23.00")), d("0"))
        val paidByCard = RebookTender(listOf(card to d("23.00")), d("0"))
        assertTrue(paidByCard.changesFrom(paidByCash))
    }

    @Test
    fun `a tip-only change with identical payments is a change`() {
        val withTip = RebookTender(listOf(card to d("23.00")), d("1.00"))
        assertTrue(withTip.changesFrom(original))
    }

    @Test
    fun `voucher numbers in a different order is no change`() {
        val a = RebookTender(listOf(voucher to d("23.00")), d("0"), vouchers = listOf("G2026-0001", "G2026-0002"))
        val b = RebookTender(listOf(voucher to d("23.00")), d("0"), vouchers = listOf("G2026-0002", "G2026-0001"))
        assertFalse(a.changesFrom(b))
    }

    @Test
    fun `balances holds across scales`() {
        assertTrue(RebookTender(listOf(card to d("25.0")), d("2.00")).balances(billTotal = d("23.00")))
    }

    @Test
    fun `a tip with three decimals is not well-formed`() {
        assertFalse(RebookTender(listOf(card to d("25.00")), d("2.001")).isWellFormed())
    }

    @Test
    fun `only a tip below the original's lowers it, across scales`() {
        val tipped = RebookTender(listOf(card to d("25.00")), d("2.00"))
        assertTrue(RebookTender(listOf(card to d("23.00")), d("0.00")).lowersTipFrom(tipped))
        assertTrue(RebookTender(listOf(card to d("24.99")), d("1.99")).lowersTipFrom(tipped))
        assertFalse(RebookTender(listOf(cash to d("25.00")), d("2.0")).lowersTipFrom(tipped), "kept")
        assertFalse(RebookTender(listOf(card to d("26.00")), d("3.00")).lowersTipFrom(tipped), "raised")
    }
}
