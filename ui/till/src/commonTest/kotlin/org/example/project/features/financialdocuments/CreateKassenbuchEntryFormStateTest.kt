package org.example.project.features.financialdocuments

import kotlinx.datetime.LocalDate
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.utils.KassenbuchBelegDateViolation
import org.example.project.features.financialdocuments.presentation.state.CreateKassenbuchEntryFormState
import org.example.project.features.financialdocuments.presentation.state.EntryLineState
import org.example.project.money.Decimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** E2E-2026-09-18 #6: "150,00" typed on the till booked as zero and blocked the form. */
class CreateKassenbuchEntryFormStateTest {
    private fun formWith(vararg amounts: String) =
        CreateKassenbuchEntryFormState().apply {
            onEntryTypeSelected(KassenbuchEntryType.CHANGE_FUND)
            description = "Wechselgeld"
            setFile(byteArrayOf(1), "beleg.pdf")
            lines = amounts.map { EntryLineState(vatRate = "0", amount = it) }
        }

    @Test
    fun aGermanCommaAmountCounts() {
        val form = formWith("150,00")
        assertEquals(Decimal.parse("150.00"), form.total)
        assertTrue(form.totalIsPositive)
        assertTrue(form.canSubmit)
    }

    @Test
    fun theWireAmountIsCanonicalDotDecimal() {
        assertEquals(
            "150.00",
            formWith("150,00")
                .buildRequest()
                .lines
                .single()
                .amount,
        )
    }

    private fun submittedRate(
        type: KassenbuchEntryType,
        storedRate: String,
    ) = CreateKassenbuchEntryFormState()
        .apply {
            onEntryTypeSelected(type)
            lines = listOf(EntryLineState(vatRate = storedRate, amount = "915,00"))
        }.buildRequest()
        .lines
        .single()
        .vatRate

    @Test
    fun aNonTaxableTypeSubmitsZeroNotTheStored19Default() {
        // Bankeinzahlung: the picker is hidden, so the "19" default must not reach the server.
        assertEquals("0", submittedRate(KassenbuchEntryType.CASH_TRANSFER_OUT, "19"))
        assertEquals("0", submittedRate(KassenbuchEntryType.PRIVATE_DEPOSIT, "19"))
    }

    @Test
    fun aRateCarryingTypeSubmitsItsPickedRate() {
        assertEquals("7", submittedRate(KassenbuchEntryType.CASH_EXPENSE, "7"))
    }

    @Test
    fun mixedSeparatorsAcrossLinesSumExactly() {
        assertEquals(Decimal.parse("20.05"), formWith("10,02", "10.03").total)
    }

    @Test
    fun anUnparseableLineIsFlaggedOnThatLineAndBlocksSubmit() {
        val form = formWith("150,00", "12a")
        assertFalse(form.lineAmountInvalid(0))
        assertTrue(form.lineAmountInvalid(1))
        assertFalse(form.canSubmit)
    }

    @Test
    fun aBlankLineIsNotFlaggedAsInvalidOnlyAsIncomplete() {
        val form = formWith("")
        assertFalse(form.lineAmountInvalid(0))
        assertFalse(form.canSubmit)
    }

    // ---- Beleg date: the till applies the server's rule --------------------

    private val today = LocalDate(2026, 9, 21)

    @Test
    fun aBlankDateIsAcceptedBecauseTheServerBooksToday() {
        assertNull(formWith("10,00").belegDateViolation(today))
    }

    @Test
    fun aReceiptFromYesterdayIsAccepted() {
        val form = formWith("10,00").apply { date = "20.09.2026" }
        assertNull(form.belegDateViolation(today))
    }

    @Test
    fun aFutureDateIsRefused() {
        val form = formWith("10,00").apply { date = "22.09.2026" }
        assertEquals(KassenbuchBelegDateViolation.FUTURE, form.belegDateViolation(today))
    }

    @Test
    fun aDateOlderThanTheSyncedLimitIsRefused() {
        val form =
            formWith("10,00").apply {
                maxBelegAgeDays = 10
                date = "10.09.2026"
            }
        assertEquals(KassenbuchBelegDateViolation.TOO_OLD, form.belegDateViolation(today))
        form.date = "11.09.2026"
        assertNull(form.belegDateViolation(today), "the limit is inclusive")
    }

    // ---- Backdating override: a reason unlocks a date past the limit ------

    @Test
    fun aDatePastTheLimitNeedsAReasonAndBlocksSubmitWithoutOne() {
        val form = formWith("10,00").apply { date = "01.08.2026" } // 51 days before 21.09.

        assertTrue(form.needsLateBookingReason(today))
        assertTrue(form.belegDateBlocksSubmit(today), "no reason, no late booking")

        form.lateBookingReason = "Lieferantenrechnung verspätet erhalten"
        assertFalse(form.belegDateBlocksSubmit(today))
    }

    @Test
    fun aDateMoreThanAYearBackCannotBeUnlockedByAReason() {
        val form =
            formWith("10,00").apply {
                date = "01.09.2025"
                lateBookingReason = "Tippfehler?"
            }

        assertFalse(form.needsLateBookingReason(today))
        assertTrue(form.belegDateBlocksSubmit(today), "past the one-year ceiling nothing is bookable")
    }

    @Test
    fun aReasonOnADateInsideTheLimitIsNotSent() {
        val form =
            formWith("10,00").apply {
                date = "20.09.2026"
                lateBookingReason = "nicht nötig"
            }

        assertFalse(form.needsLateBookingReason(today))
        assertFalse(form.belegDateBlocksSubmit(today))
    }

    // --- F59: the Kassenminus ceiling -----------------------------------------------------------

    private fun expenseOf(amount: String) =
        CreateKassenbuchEntryFormState().apply {
            onEntryTypeSelected(KassenbuchEntryType.CASH_EXPENSE)
            description = "Einkauf"
            setFile(byteArrayOf(1), "beleg.pdf")
            lines = listOf(EntryLineState(vatRate = "19", amount = amount))
        }

    @Test
    fun aWithdrawalAboveTheAvailableCashBlocksSubmit() {
        val form = expenseOf("40,01").apply { availableCash = Decimal.parse("40.00") }
        assertTrue(form.isOutflow)
        assertTrue(form.exceedsAvailableCash)
        assertFalse(form.canSubmit)
    }

    @Test
    fun negativeControlTheLastCentIsPayable() {
        val form = expenseOf("40,00").apply { availableCash = Decimal.parse("40.00") }
        assertFalse(form.exceedsAvailableCash)
        assertTrue(form.canSubmit)
    }

    @Test
    fun anInflowIsNeverBlockedByAnEmptyDrawer() {
        val form = formWith("150,00").apply { availableCash = Decimal.ZERO }
        assertFalse(form.isOutflow)
        assertFalse(form.exceedsAvailableCash)
        assertTrue(form.canSubmit)
    }

    @Test
    fun anUnknownCeilingDoesNotBlockTheServerStillGuards() {
        val form = expenseOf("999,00")
        assertNull(form.availableCash)
        assertFalse(form.exceedsAvailableCash)
        assertTrue(form.canSubmit)
    }
}
