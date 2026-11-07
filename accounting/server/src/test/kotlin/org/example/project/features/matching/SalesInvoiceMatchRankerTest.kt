package org.example.project.features.matching

import kotlinx.datetime.LocalDate
import org.example.project.features.matching.data.service.MatchSignal
import org.example.project.features.matching.data.service.MatchStrength
import org.example.project.features.matching.data.service.SalesInvoiceCandidate
import org.example.project.features.matching.data.service.SalesInvoiceMatchRanker
import org.example.project.features.matching.data.service.TransactionFacts
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The AR ladder: which STANDALONE invoices a credit payment might settle.
 *
 * The reference signals (1 and 2) hit far more often here than on the supplier side, because the
 * invoice number is printed as the Verwendungszweck — the payer does not have to type it right, the
 * shop wrote it for them.
 */
@DisplayName("Sales invoice match ranker")
class SalesInvoiceMatchRankerTest {
    private val debtorId = 42L
    private val names = mapOf(debtorId to "musterfirma")

    private fun payment(
        amount: String = "119.00",
        creditDebit: String = "CREDIT",
        counterpartyName: String? = "Musterfirma GmbH",
        remittanceUnstructured: String? = null,
        remittanceStructuredRef: String? = null,
        endToEndId: String? = null,
    ) = TransactionFacts(
        id = 1L,
        valueDate = LocalDate(2026, 3, 1),
        amount = BigDecimal(amount),
        creditDebit = creditDebit,
        counterpartyName = counterpartyName,
        remittanceUnstructured = remittanceUnstructured,
        remittanceStructuredRef = remittanceStructuredRef,
        endToEndId = endToEndId,
    )

    private fun invoice(
        id: Long,
        invoiceNumber: String? = null,
        paymentReference: String? = null,
        openAmount: String = "119.00",
        debtorId: Long? = this@SalesInvoiceMatchRankerTest.debtorId,
        dueDate: LocalDate? = null,
    ) = SalesInvoiceCandidate(
        id = id,
        invoiceNumber = invoiceNumber,
        paymentReference = paymentReference,
        debtorId = debtorId,
        issueDate = LocalDate(2026, 2, 1),
        openAmount = BigDecimal(openAmount),
        dueDate = dueDate,
    )

    @Test
    fun `a structured reference equal to an invoice number is an exact match`() {
        val suggestions =
            SalesInvoiceMatchRanker.rank(
                transaction = payment(remittanceStructuredRef = "RG-2027-00001"),
                candidates = listOf(invoice(id = 7L, invoiceNumber = "RG-2027-00001"), invoice(id = 8L, invoiceNumber = "RG-2027-00002")),
                debtorNormalizedNames = names,
            )
        assertEquals(1, suggestions.size)
        assertEquals(listOf(7L), suggestions.single().invoiceIds)
        assertEquals(MatchSignal.STRUCTURED_REFERENCE, suggestions.single().signal)
        assertEquals(MatchStrength.EXACT, suggestions.single().strength)
    }

    @Test
    fun `an end-to-end id equal to the payment reference is an exact match`() {
        val suggestions =
            SalesInvoiceMatchRanker.rank(
                transaction = payment(endToEndId = "RG-2027-00001"),
                candidates = listOf(invoice(id = 7L, paymentReference = "RG-2027-00001")),
                debtorNormalizedNames = names,
            )
        assertEquals(1, suggestions.size)
        assertEquals(MatchSignal.STRUCTURED_REFERENCE, suggestions.single().signal)
    }

    @Test
    fun `an invoice number found in free text is an exact match`() {
        val suggestions =
            SalesInvoiceMatchRanker.rank(
                transaction = payment(remittanceUnstructured = "Danke, RG-2027-00001"),
                candidates = listOf(invoice(id = 7L, invoiceNumber = "RG-2027-00001")),
                debtorNormalizedNames = names,
            )
        assertEquals(1, suggestions.size)
        assertEquals(MatchSignal.REMITTANCE_TEXT, suggestions.single().signal)
        assertEquals(MatchStrength.EXACT, suggestions.single().strength)
    }

    @Test
    fun `the debtor name signal is medium when name, amount and due date all agree`() {
        val suggestions =
            SalesInvoiceMatchRanker.rank(
                transaction = payment(counterpartyName = "Musterfirma GmbH", amount = "119.00"),
                candidates = listOf(invoice(id = 7L, openAmount = "119.00", dueDate = LocalDate(2026, 3, 5))),
                debtorNormalizedNames = names,
            )
        assertEquals(1, suggestions.size)
        assertEquals(MatchSignal.DEBTOR_NAME_AMOUNT_DATE, suggestions.single().signal)
        assertEquals(MatchStrength.MEDIUM, suggestions.single().strength)
    }

    @Test
    fun `the debtor name signal stays silent when the amount differs`() {
        val suggestions =
            SalesInvoiceMatchRanker.rank(
                transaction = payment(counterpartyName = "Musterfirma GmbH", amount = "119.00"),
                candidates = listOf(invoice(id = 7L, openAmount = "120.00", dueDate = LocalDate(2026, 3, 5))),
                debtorNormalizedNames = names,
            )
        assertTrue(suggestions.none { it.signal == MatchSignal.DEBTOR_NAME_AMOUNT_DATE })
    }

    @Test
    fun `a one-off guest with no debtor id has no name key and is skipped by the name signal`() {
        val suggestions =
            SalesInvoiceMatchRanker.rank(
                transaction = payment(counterpartyName = "Musterfirma GmbH"),
                candidates = listOf(invoice(id = 7L, debtorId = null, dueDate = LocalDate(2026, 3, 5))),
                debtorNormalizedNames = emptyMap(),
            )
        assertTrue(suggestions.none { it.signal == MatchSignal.DEBTOR_NAME_AMOUNT_DATE })
    }

    @Test
    fun `a subset of open invoices summing to the payment is a medium subset-sum match`() {
        val suggestions =
            SalesInvoiceMatchRanker.rank(
                transaction = payment(amount = "300.00"),
                candidates =
                    listOf(
                        invoice(id = 7L, openAmount = "100.00"),
                        invoice(id = 8L, openAmount = "200.00"),
                        invoice(id = 9L, openAmount = "50.00"),
                    ),
                debtorNormalizedNames = names,
            )
        assertTrue(suggestions.any { it.signal == MatchSignal.SUBSET_SUM && it.invoiceIds.toSet() == setOf(7L, 8L) })
    }

    @Test
    fun `a DEBIT transaction produces nothing — the direction guard`() {
        val suggestions =
            SalesInvoiceMatchRanker.rank(
                transaction = payment(creditDebit = "DEBIT", remittanceStructuredRef = "RG-2027-00001"),
                candidates = listOf(invoice(id = 7L, invoiceNumber = "RG-2027-00001")),
                debtorNormalizedNames = names,
            )
        assertTrue(suggestions.isEmpty(), "an outgoing payment must never be suggested against a receivable")
    }

    @Test
    fun `nothing firing returns an empty list, which is the correct answer`() {
        val suggestions =
            SalesInvoiceMatchRanker.rank(
                transaction = payment(remittanceUnstructured = "Vielen Dank für die Einladung"),
                candidates = listOf(invoice(id = 7L, invoiceNumber = "RG-2027-00001")),
                debtorNormalizedNames = names,
            )
        assertTrue(suggestions.isEmpty(), "no signal firing is a normal outcome, not a failure")
    }
}
