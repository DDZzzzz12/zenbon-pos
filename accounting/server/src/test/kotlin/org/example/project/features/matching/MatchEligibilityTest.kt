package org.example.project.features.matching

import kotlinx.datetime.LocalDate
import org.example.project.features.matching.data.service.InvoiceCandidate
import org.example.project.features.matching.data.service.TransactionFacts
import org.example.project.features.matching.data.service.eligibleCandidates
import org.example.project.features.matching.data.service.isEligible
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The guards of spec §7 — the conditions under which no suggestion may be produced at all.
 *
 * They are a filter rather than a weak signal on purpose: a suppressed candidate must be impossible
 * to surface, not merely unlikely to win.
 */
@DisplayName("Match eligibility")
class MatchEligibilityTest {
    private fun payment(
        creditDebit: String = "DEBIT",
        reversalIndicator: Boolean = false,
        hasBankAccount: Boolean = true,
        isDuplicate: Boolean = false,
    ) = TransactionFacts(
        id = 1L,
        valueDate = LocalDate(2026, 3, 1),
        amount = BigDecimal("119.00"),
        creditDebit = creditDebit,
        reversalIndicator = reversalIndicator,
        hasBankAccount = hasBankAccount,
        isDuplicate = isDuplicate,
    )

    private fun invoice(
        id: Long = 1L,
        issueDate: LocalDate? = LocalDate(2026, 2, 1),
        openAmount: String? = "119.00",
    ) = InvoiceCandidate(
        id = id,
        invoiceNumber = "RE-2026-000$id",
        issueDate = issueDate,
        openAmount = openAmount?.let { BigDecimal(it) },
    )

    @Test
    fun `an ordinary outgoing payment on a resolved statement is eligible`() {
        assertTrue(isEligible(payment()))
    }

    @Test
    fun `a reversal is not eligible`() {
        assertFalse(isEligible(payment(reversalIndicator = true)), "a reversal settles nothing; it un-settles")
    }

    @Test
    fun `a transaction whose statement has no bank account is not eligible`() {
        assertFalse(
            isEligible(payment(hasBankAccount = false)),
            "no account means no shop, so the cross-shop guard cannot even be evaluated",
        )
    }

    @Test
    fun `a duplicate delivery is not eligible`() {
        assertFalse(isEligible(payment(isDuplicate = true)))
    }

    @Test
    fun `an incoming credit is not eligible for invoice matching`() {
        assertFalse(
            isEligible(payment(creditDebit = "CREDIT")),
            "money arriving is a customer payment or a refund; it does not settle a supplier invoice",
        )
    }

    @Test
    fun `an unrecognised direction is not eligible`() {
        assertFalse(isEligible(payment(creditDebit = "")), "only a debit is known to be an outgoing payment")
    }

    @Test
    fun `a transaction in another shop is not eligible`() {
        assertFalse(
            isEligible(payment(), sameShop = false),
            "the parameter records the guard until invoice.shop_id exists",
        )
    }

    @Test
    fun `an invoice with no open amount is dropped`() {
        val kept = eligibleCandidates(listOf(invoice(id = 1L), invoice(id = 2L, openAmount = null)), bookedFrom = null)
        assertEquals(listOf(1L), kept.map { it.id })
    }

    @Test
    fun `a fully allocated invoice is dropped`() {
        val candidates =
            listOf(
                invoice(id = 1L),
                invoice(id = 2L, openAmount = "0.00"),
                invoice(id = 3L, openAmount = "-5.00"),
            )
        assertEquals(listOf(1L), eligibleCandidates(candidates, bookedFrom = null).map { it.id })
    }

    @Test
    fun `an invoice issued before the Buchungsbeginn is dropped`() {
        val candidates =
            listOf(
                invoice(id = 1L, issueDate = LocalDate(2026, 1, 31)),
                invoice(id = 2L, issueDate = LocalDate(2026, 2, 1)),
                invoice(id = 3L, issueDate = LocalDate(2026, 2, 2)),
            )
        assertEquals(
            listOf(2L, 3L),
            eligibleCandidates(candidates, bookedFrom = LocalDate(2026, 2, 1)).map { it.id },
            "the Buchungsbeginn day itself is inside the books",
        )
    }

    @Test
    fun `an invoice with no issue date is dropped only when a Buchungsbeginn is set`() {
        val undated = listOf(invoice(id = 1L, issueDate = null))
        assertEquals(listOf(1L), eligibleCandidates(undated, bookedFrom = null).map { it.id })
        assertEquals(
            emptyList(),
            eligibleCandidates(undated, bookedFrom = LocalDate(2026, 2, 1)).map { it.id },
            "an undated invoice cannot be shown to fall inside the books",
        )
    }

    @Test
    fun `with no Buchungsbeginn nothing is dropped on date grounds`() {
        val candidates = listOf(invoice(id = 1L, issueDate = LocalDate(2019, 1, 1)), invoice(id = 2L))
        assertEquals(listOf(1L, 2L), eligibleCandidates(candidates, bookedFrom = null).map { it.id })
    }
}
