package org.example.project.features.matching

import kotlinx.datetime.LocalDate
import org.example.project.features.matching.data.service.InvoiceCandidate
import org.example.project.features.matching.data.service.MatchSignal
import org.example.project.features.matching.data.service.MatchStrength
import org.example.project.features.matching.data.service.TransactionFacts
import org.example.project.features.matching.data.service.supplierNameSuggestions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Signal 6: amount exact, supplier name agreeing after normalisation, and the payment landing near
 * the invoice's due date. `MEDIUM`, and never more — there is no identifier anywhere in it.
 *
 * "Fuzzy" here means the **normalised keys match**, not an edit distance: an edit distance would
 * additionally make different companies agree, and at this strength nothing would catch the error.
 */
@DisplayName("Supplier name signal")
class SupplierNameSignalTest {
    private val supplierId = 42L
    private val otherSupplierId = 43L
    private val dueDate = LocalDate(2026, 3, 1)

    /** What `supplier.normalized_name` holds for "Metro Cash & Carry GmbH". */
    private val supplierKeys = mapOf(supplierId to "metro cash carry", otherSupplierId to "getränke müller")

    private fun payment(
        amount: String = "119.00",
        valueDate: LocalDate = dueDate,
        counterpartyName: String? = "METRO Cash Carry GmbH",
    ) = TransactionFacts(
        id = 1L,
        valueDate = valueDate,
        amount = BigDecimal(amount),
        creditDebit = "DEBIT",
        counterpartyName = counterpartyName,
        counterpartyIban = "DE02120300000000202051",
    )

    private fun invoice(
        id: Long = 1L,
        supplierId: Long? = this.supplierId,
        openAmount: String? = "119.00",
        dueDate: LocalDate? = this.dueDate,
    ) = InvoiceCandidate(
        id = id,
        supplierId = supplierId,
        issueDate = LocalDate(2026, 2, 1),
        openAmount = openAmount?.let { BigDecimal(it) },
        dueDate = dueDate,
    )

    @Test
    fun `an agreeing name, an exact amount and a payment near the due date is medium`() {
        val suggestions = supplierNameSuggestions(payment(), listOf(invoice()), supplierKeys)

        assertEquals(1, suggestions.size)
        val only = suggestions.single()
        assertEquals(listOf(1L), only.invoiceIds)
        assertEquals(MatchSignal.SUPPLIER_NAME_AMOUNT_DATE, only.signal)
        assertEquals(MatchStrength.MEDIUM, only.strength)
    }

    @Test
    fun `a different company is not matched, however similar it looks`() {
        // "Metro Cash & Carry" vs "Getränke Müller": normalised keys disagree, so nothing fires.
        val suggestions =
            supplierNameSuggestions(
                payment(counterpartyName = "Getränke Müller GmbH"),
                listOf(invoice()),
                supplierKeys,
            )

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `a counterparty with no name at all produces nothing`() {
        assertTrue(supplierNameSuggestions(payment(counterpartyName = null), listOf(invoice()), supplierKeys).isEmpty())
    }

    @Test
    fun `an amount that is not the invoice's open amount produces nothing`() {
        val suggestions = supplierNameSuggestions(payment(amount = "119.01"), listOf(invoice()), supplierKeys)

        assertTrue(suggestions.isEmpty(), "signal 6 has no tolerance band — the amount is the whole of its arithmetic")
    }

    @Test
    fun `a payment outside the window produces nothing`() {
        val suggestions =
            supplierNameSuggestions(payment(valueDate = LocalDate(2026, 3, 12)), listOf(invoice()), supplierKeys)

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `exactly ten days is inside the window and eleven is not`() {
        val ten = supplierNameSuggestions(payment(valueDate = LocalDate(2026, 3, 11)), listOf(invoice()), supplierKeys)
        assertEquals(1, ten.size, "a reminder paid a week and a half late is still this invoice")

        val eleven = supplierNameSuggestions(payment(valueDate = LocalDate(2026, 3, 12)), listOf(invoice()), supplierKeys)
        assertTrue(eleven.isEmpty(), "beyond ten days it starts reaching into the next month's invoice")

        val elevenEarly =
            supplierNameSuggestions(payment(valueDate = LocalDate(2026, 2, 18)), listOf(invoice()), supplierKeys)
        assertTrue(elevenEarly.isEmpty(), "the window is symmetric — eleven days early is outside too")
    }

    @Test
    fun `an invoice with no due date is not suggested`() {
        val suggestions = supplierNameSuggestions(payment(), listOf(invoice(dueDate = null)), supplierKeys)

        assertTrue(suggestions.isEmpty(), "it cannot be shown to fall in the window, and an unprovable match is worse than none")
    }

    @Test
    fun `two invoices of that supplier, same amount, both in the window, are both returned`() {
        val suggestions =
            supplierNameSuggestions(
                payment(valueDate = LocalDate(2026, 3, 5)),
                listOf(invoice(id = 1L, dueDate = LocalDate(2026, 3, 1)), invoice(id = 2L, dueDate = LocalDate(2026, 3, 10))),
                supplierKeys,
            )

        assertEquals(
            listOf(listOf(1L), listOf(2L)),
            suggestions.map { it.invoiceIds },
            "two candidates stay two candidates; nothing here picks a default",
        )
    }

    @Test
    fun `an invoice belonging to another supplier is not reached`() {
        val suggestions =
            supplierNameSuggestions(payment(), listOf(invoice(supplierId = otherSupplierId)), supplierKeys)

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `an ineligible transaction produces nothing`() {
        val suggestions =
            supplierNameSuggestions(payment().copy(reversalIndicator = true), listOf(invoice()), supplierKeys)

        assertTrue(suggestions.isEmpty(), "a reversal settles nothing")
    }

    @Test
    fun `the amount comparison is compareTo, not equals`() {
        val suggestions = supplierNameSuggestions(payment(amount = "119.0000"), listOf(invoice()), supplierKeys)

        assertEquals(1, suggestions.size, "119.0000 and 119.00 are the same amount at different scales")
    }
}
