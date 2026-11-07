package org.example.project.features.matching

import kotlinx.datetime.LocalDate
import org.example.project.features.matching.data.service.TransactionFacts
import org.example.project.features.matching.data.service.findReversedEntry
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which earlier entry a returned direct debit undoes.
 *
 * The discipline is the suggestion ladder's: strong identifiers first, and **silence when unsure**.
 * Voiding the wrong entry would un-pay an invoice that was actually paid — the same invisible wrong
 * state this whole slice exists to prevent, in the opposite direction.
 */
@DisplayName("Reversal pairing")
class ReversalPairingTest {
    private fun entry(
        id: Long,
        valueDate: LocalDate = LocalDate(2026, 3, 1),
        amount: String = "119.00",
        creditDebit: String = "DEBIT",
        counterpartyIban: String? = null,
        endToEndId: String? = null,
        accountServicerReference: String? = null,
        reversalIndicator: Boolean = false,
    ) = TransactionFacts(
        id = id,
        valueDate = valueDate,
        amount = BigDecimal(amount),
        creditDebit = creditDebit,
        counterpartyIban = counterpartyIban,
        endToEndId = endToEndId,
        accountServicerReference = accountServicerReference,
        reversalIndicator = reversalIndicator,
    )

    /** A return: a credit coming back on 20 March against a debit made earlier. */
    private fun reversal(
        valueDate: LocalDate = LocalDate(2026, 3, 20),
        amount: String = "119.00",
        counterpartyIban: String? = null,
        endToEndId: String? = null,
        accountServicerReference: String? = null,
    ) = entry(
        id = 99L,
        valueDate = valueDate,
        amount = amount,
        creditDebit = "CREDIT",
        counterpartyIban = counterpartyIban,
        endToEndId = endToEndId,
        accountServicerReference = accountServicerReference,
        reversalIndicator = true,
    )

    @Test
    fun `the same end-to-end id pairs even when the amounts differ`() {
        val original = entry(id = 1L, amount = "119.00", endToEndId = "E2E-4711")
        val partialReturn = reversal(amount = "60.00", endToEndId = "E2E-4711")

        assertEquals<Long?>(
            original.id,
            findReversedEntry(partialReturn, listOf(original))?.id,
            "the payer's own reference is carried through the return; a partial return is still a return",
        )
    }

    @Test
    fun `the same account servicer reference pairs when no end-to-end id matches`() {
        val original = entry(id = 1L, endToEndId = "E2E-OTHER", accountServicerReference = "ASR-1")
        val returned = reversal(endToEndId = "E2E-MINE", accountServicerReference = "ASR-1")

        assertEquals(1L, findReversedEntry(returned, listOf(original))?.id)
    }

    @Test
    fun `the end-to-end id wins over the account servicer reference`() {
        val byEndToEnd = entry(id = 1L, endToEndId = "E2E-4711")
        val byServicerRef = entry(id = 2L, accountServicerReference = "ASR-1")
        val returned = reversal(endToEndId = "E2E-4711", accountServicerReference = "ASR-1")

        assertEquals<Long?>(
            1L,
            findReversedEntry(returned, listOf(byServicerRef, byEndToEnd))?.id,
            "the payer's reference outranks the bank's, and the order of the list must not decide",
        )
    }

    @Test
    fun `with no identifiers the same iban amount and opposite direction pair inside the window`() {
        val original = entry(id = 1L, valueDate = LocalDate(2026, 3, 15), counterpartyIban = "DE02120300000000202051")
        val returned = reversal(counterpartyIban = "DE02120300000000202051")

        assertEquals(1L, findReversedEntry(returned, listOf(original))?.id)
    }

    @Test
    fun `the same direction is not a return`() {
        val secondDebit =
            entry(
                id = 1L,
                valueDate = LocalDate(2026, 3, 15),
                creditDebit = "DEBIT",
                counterpartyIban = "DE02120300000000202051",
            )
        val alsoDebit =
            entry(
                id = 99L,
                valueDate = LocalDate(2026, 3, 20),
                creditDebit = "DEBIT",
                counterpartyIban = "DE02120300000000202051",
                reversalIndicator = true,
            )

        assertNull(
            findReversedEntry(alsoDebit, listOf(secondDebit)),
            "a second debit to the same supplier for the same amount is another payment, not a return",
        )
    }

    @Test
    fun `an entry forty days earlier is outside the window`() {
        val old = entry(id = 1L, valueDate = LocalDate(2026, 2, 8), counterpartyIban = "DE02120300000000202051")
        val returned = reversal(counterpartyIban = "DE02120300000000202051")

        assertNull(findReversedEntry(returned, listOf(old)), "the window is 30 days")
    }

    @Test
    fun `two equally plausible candidates pair with nothing`() {
        val first = entry(id = 1L, valueDate = LocalDate(2026, 3, 10), counterpartyIban = "DE02120300000000202051")
        val second = entry(id = 2L, valueDate = LocalDate(2026, 3, 15), counterpartyIban = "DE02120300000000202051")
        val returned = reversal(counterpartyIban = "DE02120300000000202051")

        assertNull(
            findReversedEntry(returned, listOf(first, second)),
            "two identical debits to one supplier in a month is ordinary; picking either is a coin flip",
        )
    }

    @Test
    fun `a candidate that is itself a reversal is never the reversed entry`() {
        val anotherReturn =
            entry(
                id = 1L,
                valueDate = LocalDate(2026, 3, 15),
                creditDebit = "DEBIT",
                counterpartyIban = "DE02120300000000202051",
                endToEndId = "E2E-4711",
                reversalIndicator = true,
            )
        val returned = reversal(counterpartyIban = "DE02120300000000202051", endToEndId = "E2E-4711")

        assertNull(findReversedEntry(returned, listOf(anotherReturn)))
    }

    @Test
    fun `no candidates pair with nothing`() {
        assertNull(findReversedEntry(reversal(endToEndId = "E2E-4711"), emptyList()))
    }

    @Test
    fun `a reversal never pairs with itself`() {
        val itself = reversal(counterpartyIban = "DE02120300000000202051", endToEndId = "E2E-4711")

        assertNull(findReversedEntry(itself, listOf(itself)))
    }

    @Test
    fun `two candidates carrying the same identifier pair with nothing`() {
        val first = entry(id = 1L, endToEndId = "E2E-4711")
        val second = entry(id = 2L, endToEndId = "E2E-4711")
        val returned = reversal(endToEndId = "E2E-4711")

        assertNull(
            findReversedEntry(returned, listOf(first, second)),
            "a reused reference identifies nothing; the same coin flip as signal 3",
        )
    }

    @Test
    fun `a blank identifier matches nothing`() {
        val blank = entry(id = 1L, endToEndId = "", accountServicerReference = "")
        val returned = reversal(endToEndId = "", accountServicerReference = "")

        assertNull(findReversedEntry(returned, listOf(blank)), "an absent reference is not a match")
    }
}
