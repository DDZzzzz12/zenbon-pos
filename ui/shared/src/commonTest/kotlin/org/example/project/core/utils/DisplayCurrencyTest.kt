package org.example.project.core.utils

import org.example.project.ui.components.formatMoneyForDisplay
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `String.toFormattedCurrency` appended the ISO code to the server's decimal ("9.40 EUR") while its
 * Decimal twin rendered "9,40 €", so one screen could show both spellings of money. These pin the
 * Compose-free half the composable now delegates to.
 */
class DisplayCurrencyTest {
    @Test
    fun aServerAmountRendersLikeEveryOtherAmount() {
        assertEquals("9,40 €", formatCurrencyAmount("9.40", "EUR"))
        assertEquals(formatMoneyForDisplay("1234.5", "EUR"), formatCurrencyAmount("1234.5", "EUR"))
    }

    @Test
    fun aDeletedAmountKeepsItsLeadingMinus() {
        assertEquals("-9,40 €", formatCurrencyAmount("9.40", "EUR", deleted = true))
    }

    @Test
    fun anUnparseableAmountIsShownUnchanged() {
        assertEquals("n/a", formatCurrencyAmount("n/a", "EUR"))
        assertEquals("", formatCurrencyAmount("", "EUR"))
    }

    @Test
    fun withoutACurrencyItIsABareAmount() {
        // The billing keypad's empty-input placeholder (PosBillingPaymentPanel).
        assertEquals("0,00", formatMoneyForDisplay("0", ""))
    }
}
