package org.example.project.features.matching

import kotlinx.datetime.LocalDate
import org.example.project.features.matching.data.service.InvoiceCandidate
import org.example.project.features.matching.data.service.InvoiceMatchRanker
import org.example.project.features.matching.data.service.MatchSignal
import org.example.project.features.matching.data.service.MatchStrength
import org.example.project.features.matching.data.service.TransactionFacts
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ladder: which invoices a payment might settle, strongest first.
 *
 * The cases that matter most are the ones asserting **silence** — 6 (two open invoices, so "single"
 * does not hold), 8 (a subset larger than four), 10 (nothing fires) and 11 (a genuine tie stays a
 * tie). A wrong suggestion costs more than no suggestion.
 */
@DisplayName("Invoice match ranker")
class InvoiceMatchRankerTest {
    private val supplierId = 42L
    private val otherSupplierId = 43L

    private fun payment(
        amount: String = "119.00",
        remittanceUnstructured: String? = null,
        remittanceStructuredRef: String? = null,
        endToEndId: String? = null,
        counterpartyIban: String? = "DE02120300000000202051",
    ) = TransactionFacts(
        id = 1L,
        valueDate = LocalDate(2026, 3, 1),
        amount = BigDecimal(amount),
        creditDebit = "DEBIT",
        counterpartyIban = counterpartyIban,
        remittanceUnstructured = remittanceUnstructured,
        remittanceStructuredRef = remittanceStructuredRef,
        endToEndId = endToEndId,
    )

    private fun invoice(
        id: Long,
        invoiceNumber: String? = null,
        paymentReference: String? = null,
        openAmount: String = "119.00",
        supplierId: Long? = null,
    ) = InvoiceCandidate(
        id = id,
        invoiceNumber = invoiceNumber,
        paymentReference = paymentReference,
        supplierId = supplierId,
        issueDate = LocalDate(2026, 2, 1),
        openAmount = BigDecimal(openAmount),
    )

    // 1
    @Test
    fun `a structured reference equal to an invoice number is an exact match`() {
        val suggestions =
            InvoiceMatchRanker.rank(
                transaction = payment(remittanceStructuredRef = "RE-2026-0001"),
                candidates = listOf(invoice(id = 7L, invoiceNumber = "RE-2026-0001"), invoice(id = 8L, invoiceNumber = "RE-2026-0002")),
            )
        assertEquals(1, suggestions.size)
        assertEquals(listOf(7L), suggestions.single().invoiceIds)
        assertEquals(MatchSignal.STRUCTURED_REFERENCE, suggestions.single().signal)
        assertEquals(MatchStrength.EXACT, suggestions.single().strength)
        assertEquals(0, BigDecimal("119.00").compareTo(suggestions.single().amount))
    }

    // 2
    @Test
    fun `an end-to-end id equal to a payment reference is an exact match`() {
        val suggestions =
            InvoiceMatchRanker.rank(
                transaction = payment(endToEndId = "2026/0001"),
                candidates = listOf(invoice(id = 7L, invoiceNumber = "X-9", paymentReference = "RE 2026 0001")),
            )
        assertEquals(listOf(7L), suggestions.single().invoiceIds)
        assertEquals(MatchSignal.STRUCTURED_REFERENCE, suggestions.single().signal)
        assertEquals(MatchStrength.EXACT, suggestions.single().strength)
    }

    // 3
    @Test
    fun `an invoice number embedded in a remittance sentence is an exact match`() {
        val suggestions =
            InvoiceMatchRanker.rank(
                transaction = payment(remittanceUnstructured = "Zahlung Rechnung RE-2026-0001 vielen Dank"),
                candidates = listOf(invoice(id = 7L, invoiceNumber = "RE-2026-0001")),
            )
        assertEquals(listOf(7L), suggestions.single().invoiceIds)
        assertEquals(MatchSignal.REMITTANCE_TEXT, suggestions.single().signal)
        assertEquals(MatchStrength.EXACT, suggestions.single().strength)
    }

    // 4
    @Test
    fun `a number mangled across several words in the remittance still matches`() {
        val suggestions =
            InvoiceMatchRanker.rank(
                transaction = payment(remittanceUnstructured = "Ueberweisung RE 2026 0001 Danke"),
                candidates = listOf(invoice(id = 7L, invoiceNumber = "RE-2026-0001")),
            )
        assertEquals(listOf(7L), suggestions.single().invoiceIds)
        assertEquals(MatchSignal.REMITTANCE_TEXT, suggestions.single().signal)
    }

    // 5
    @Test
    fun `a supplier with exactly one open invoice matching the amount is a strong match`() {
        val suggestions =
            InvoiceMatchRanker.rank(
                transaction = payment(amount = "119.00"),
                candidates =
                    listOf(
                        invoice(id = 7L, invoiceNumber = "RE-2026-0001", supplierId = supplierId),
                        invoice(id = 8L, invoiceNumber = "RE-2026-0002", supplierId = otherSupplierId),
                    ),
                counterpartySupplierId = supplierId,
            )
        assertEquals(listOf(7L), suggestions.single().invoiceIds)
        assertEquals(MatchSignal.IBAN_SINGLE_OPEN, suggestions.single().signal)
        assertEquals(MatchStrength.STRONG, suggestions.single().strength)
    }

    // 6
    @Test
    fun `signal 4 does not fire when the supplier has two open invoices`() {
        val suggestions =
            InvoiceMatchRanker.rank(
                transaction = payment(amount = "119.00"),
                candidates =
                    listOf(
                        invoice(id = 7L, invoiceNumber = "RE-2026-0001", openAmount = "119.00", supplierId = supplierId),
                        invoice(id = 8L, invoiceNumber = "RE-2026-0002", openAmount = "80.00", supplierId = supplierId),
                    ),
                counterpartySupplierId = supplierId,
            )
        assertEquals(
            emptyList(),
            suggestions,
            "'single' is load-bearing: with two open invoices the amount agreement is a coincidence we cannot rule out",
        )
    }

    // 7
    @Test
    fun `two open invoices summing to the payment are a medium subset match`() {
        val suggestions =
            InvoiceMatchRanker.rank(
                transaction = payment(amount = "119.00"),
                candidates =
                    listOf(
                        invoice(id = 7L, openAmount = "39.00", supplierId = supplierId),
                        invoice(id = 8L, openAmount = "80.00", supplierId = supplierId),
                        invoice(id = 9L, openAmount = "12.00", supplierId = supplierId),
                    ),
                counterpartySupplierId = supplierId,
            )
        assertEquals(1, suggestions.size)
        assertEquals(listOf(7L, 8L), suggestions.single().invoiceIds)
        assertEquals(MatchSignal.SUBSET_SUM, suggestions.single().signal)
        assertEquals(MatchStrength.MEDIUM, suggestions.single().strength)
        assertEquals(0, BigDecimal("119.00").compareTo(suggestions.single().amount))
    }

    // 8
    @Test
    fun `a subset of five is not suggested`() {
        val candidates = (1L..5L).map { invoice(id = it, openAmount = "10.00", supplierId = supplierId) }
        val suggestions =
            InvoiceMatchRanker.rank(
                transaction = payment(amount = "50.00"),
                candidates = candidates,
                counterpartySupplierId = supplierId,
            )
        assertEquals(emptyList(), suggestions, "the subset search stops at four; beyond that the arithmetic proves nothing")
    }

    // 8b — the bound that keeps the search from stalling
    @Test
    fun `the subset search is skipped entirely above the candidate bound`() {
        val candidates = (1L..21L).map { invoice(id = it, openAmount = "10.00", supplierId = supplierId) }
        val suggestions =
            InvoiceMatchRanker.rank(
                transaction = payment(amount = "40.00"),
                candidates = candidates,
                counterpartySupplierId = supplierId,
            )
        assertEquals(emptyList(), suggestions, "with more than 20 open invoices the subset signal stays quiet rather than computing")
    }

    // 9
    @Test
    fun `several firing signals are returned strongest first`() {
        val suggestions =
            InvoiceMatchRanker.rank(
                transaction = payment(amount = "119.00", remittanceStructuredRef = "RE-2026-0009"),
                candidates =
                    listOf(
                        invoice(id = 9L, invoiceNumber = "RE-2026-0009", openAmount = "119.00", supplierId = otherSupplierId),
                        invoice(id = 7L, invoiceNumber = "RE-2026-0001", openAmount = "119.00", supplierId = supplierId),
                    ),
                counterpartySupplierId = supplierId,
            )
        assertEquals(listOf(MatchStrength.EXACT, MatchStrength.STRONG), suggestions.map { it.strength })
        assertEquals(listOf(listOf(9L), listOf(7L)), suggestions.map { it.invoiceIds })
    }

    // 10
    @Test
    fun `no signal firing returns an empty list`() {
        val suggestions =
            InvoiceMatchRanker.rank(
                transaction = payment(amount = "119.00", remittanceUnstructured = "Miete Maerz"),
                candidates = listOf(invoice(id = 7L, invoiceNumber = "RE-2026-0001", openAmount = "500.00")),
            )
        assertEquals(emptyList(), suggestions, "saying nothing is a correct outcome")
    }

    // 11
    @Test
    fun `two invoices carrying the same number are both returned as candidates`() {
        val suggestions =
            InvoiceMatchRanker.rank(
                transaction = payment(remittanceStructuredRef = "RE-2026-0001"),
                candidates =
                    listOf(
                        invoice(id = 7L, invoiceNumber = "RE-2026-0001"),
                        invoice(id = 8L, invoiceNumber = "RE 2026 0001"),
                    ),
            )
        assertEquals(
            listOf(listOf(7L), listOf(8L)),
            suggestions.map { it.invoiceIds },
            "a tie is two candidates with no default, not a winner",
        )
        assertTrue(suggestions.all { it.strength == MatchStrength.EXACT })
    }

    // 12 — the guards still apply inside the ranker
    @Test
    fun `an ineligible transaction produces nothing however well it matches`() {
        val credit =
            payment(remittanceStructuredRef = "RE-2026-0001").copy(creditDebit = "CREDIT")
        assertEquals(
            emptyList(),
            InvoiceMatchRanker.rank(credit, listOf(invoice(id = 7L, invoiceNumber = "RE-2026-0001"))),
            "money arriving settles no supplier invoice, even with a perfect reference",
        )
    }

    // 13 — one invoice matched by two signals is one suggestion
    @Test
    fun `an invoice matched by both reference and remittance text appears once`() {
        val suggestions =
            InvoiceMatchRanker.rank(
                transaction =
                    payment(
                        remittanceStructuredRef = "RE-2026-0001",
                        remittanceUnstructured = "Zahlung RE-2026-0001",
                    ),
                candidates = listOf(invoice(id = 7L, invoiceNumber = "RE-2026-0001")),
            )
        assertEquals(1, suggestions.size)
        assertEquals(MatchSignal.STRUCTURED_REFERENCE, suggestions.single().signal)
    }

    // 14 — a partial payment settles only what arrived
    @Test
    fun `the suggested amount never exceeds the payment or the open amount`() {
        val suggestions =
            InvoiceMatchRanker.rank(
                transaction = payment(amount = "50.00", remittanceStructuredRef = "RE-2026-0001"),
                candidates = listOf(invoice(id = 7L, invoiceNumber = "RE-2026-0001", openAmount = "119.00")),
            )
        assertEquals(0, BigDecimal("50.00").compareTo(suggestions.single().amount))
    }
}
