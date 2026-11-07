package org.example.project.ui.screens.billing

import org.example.project.money.Decimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** NF-6 (rt55): the legacy-voucher amount is typed with the German comma; a strict parse refused "10,00". */
class TypedVoucherAmountTest {
    @Test
    fun `a German comma amount is accepted`() = assertEquals(Decimal.parse("10.00"), typedVoucherAmountOrNull("10,00"))

    @Test
    fun `a dot amount is accepted`() = assertEquals(Decimal.parse("10.00"), typedVoucherAmountOrNull("10.00"))

    @Test
    fun `blank, zero, negative and ambiguous input are refused`() {
        assertNull(typedVoucherAmountOrNull(""))
        assertNull(typedVoucherAmountOrNull("0,00"))
        assertNull(typedVoucherAmountOrNull("-5,00"))
        assertNull(typedVoucherAmountOrNull("1.000,50"))
    }
}
