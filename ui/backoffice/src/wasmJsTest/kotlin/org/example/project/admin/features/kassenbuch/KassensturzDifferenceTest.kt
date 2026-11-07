package org.example.project.admin.features.kassenbuch

import org.example.project.admin.features.kassenbuch.presentation.KassensturzUiState
import org.example.project.admin.features.kassenbuch.presentation.countedBalanceOf
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.money.Decimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the two rules the Kassensturz dialog turns on: the difference is exact, and a blank field is
 * not a zero count.
 *
 * Both fail *silently and plausibly* if broken. A difference computed through a `Double` still
 * renders a plausible number — it is simply one cent off, which is the whole class of defect the
 * `:platform-money` module exists to remove, and here it would tell an operator the safe agrees with the book
 * when it does not. And a blank field read as zero would submit a count of nothing as a count of
 * zero, booking the entire book balance as a shortage that nobody entered.
 */
class KassensturzDifferenceTest {
    private fun stateWith(
        bookBalance: String,
        counted: String,
    ) = KassensturzUiState(
        preview =
            CashBookDto.ClosePreviewDto(
                cashBookId = 2L,
                cashBookName = "Tresor",
                periodStart = "2026-08-01",
                periodEnd = "2026-08-09",
                bookBalance = bookBalance,
            ),
        countedInput = counted,
    )

    @Test
    fun differenceIsExactWhereADoubleWouldNotBe() {
        // 16.90 and 0.1 are the classic binary-fraction traps: (16.9 - 16.8) as Doubles is
        // 0.09999999999999964, which formats to "0.10" and hides a mismatch, while the cents route
        // truncates (16.9 * 100).toLong() to 1689 and invents one.
        assertEquals(Decimal.parse("0.10"), stateWith("16.80", "16.90").difference)
        assertEquals(Decimal.parse("-0.01"), stateWith("100.00", "99.99").difference)
        assertEquals(Decimal.parse("0.0000"), stateWith("1234.5678", "1234.5678").difference)
    }

    @Test
    fun aSubCentDifferenceIsNotRoundedIntoAgreement() {
        // The column is DECIMAL(19,4): a book balance carrying a quarter-cent must not be shown as
        // matching a whole-cent count. Rounding to two decimals here would print "0,00" and tell
        // the operator to close a period that does not balance.
        val difference = stateWith("128.2550", "128.25").difference
        assertNotNull(difference)
        assertFalse(difference.isZero)
        assertEquals(Decimal.parse("-0.0050"), difference)
    }

    @Test
    fun aBlankCountIsNotZero() {
        val blank = stateWith("128.50", "")
        assertNull(blank.countedBalance)
        assertNull(blank.difference)
        // Nothing typed is not a mistake, so nothing is flagged — but nothing can be submitted.
        assertFalse(blank.hasUnusableInput)
        assertFalse(blank.canConfirm)
    }

    @Test
    fun zeroIsAValidCount() {
        // An emptied safe. The whole book balance is then the shortage, and that must be bookable.
        val emptied = stateWith("128.50", "0")
        assertEquals(Decimal.ZERO, emptied.countedBalance)
        assertEquals(Decimal.parse("-128.50"), emptied.difference)
        assertTrue(emptied.canConfirm)
        assertFalse(emptied.hasUnusableInput)
    }

    @Test
    fun aGermanCommaIsAccepted() {
        // The operator types on a German keyboard; Decimal.parse is strict ASCII "digits[.digits]".
        assertEquals(Decimal.parse("128.50"), countedBalanceOf("128,50"))
        assertEquals(Decimal.parse("0.10"), stateWith("16.80", "16,90").difference)
    }

    @Test
    fun unusableInputIsRefusedRatherThanSentToBeRejected() {
        // Each of these is refused by CashBookDto.CloseRequestDto.validate() and by the server's
        // isNonNegativeMoney regex; catching them here is the client half of CLAUDE.md rule 8.
        assertNull(countedBalanceOf("-5"))
        assertNull(countedBalanceOf("12.345678"))
        assertNull(countedBalanceOf("abc"))
        assertNull(countedBalanceOf("12,50 EUR"))

        val negative = stateWith("128.50", "-5")
        assertTrue(negative.hasUnusableInput)
        assertFalse(negative.canConfirm)
    }

    @Test
    fun submittingIsBlockedWhileTheCloseIsInFlight() {
        assertFalse(stateWith("128.50", "128.50").copy(isSubmitting = true).canConfirm)
    }

    @Test
    fun theWireValueIsAPlainDecimalString() {
        // Money crosses as a string and never as a JSON number; the string must also survive the
        // DTO's own shape check, which is the same regex the server applies.
        val counted = stateWith("128.50", "128,5").countedBalance
        assertNotNull(counted)
        assertEquals("128.5", counted.toPlainString())
        assertEquals(emptyList(), CashBookDto.CloseRequestDto(counted.toPlainString()).validate())
        assertEquals(emptyList(), CashBookDto.CloseRequestDto(Decimal.ZERO.toPlainString()).validate())
    }
}
