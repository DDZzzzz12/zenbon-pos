package org.example.project.einvoice

import kotlinx.datetime.LocalDate
import org.example.project.einvoice.model.EInvoice
import org.example.project.einvoice.model.EInvoiceFormat
import org.example.project.einvoice.model.EInvoiceParty
import org.example.project.einvoice.model.EInvoiceVatBreakdown
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The model is direction-neutral: the parser produces it and the generator consumes it. That only
 * works if equality is structural, because round-trip tests (parse -> model -> generate -> parse)
 * compare two independently constructed instances.
 */
class EInvoiceModelTest {
    private fun minimal() =
        EInvoice(
            format = EInvoiceFormat.XRECHNUNG,
            invoiceNumber = "RE-2026-0001",
            issueDate = LocalDate(2026, 3, 12),
            currency = "EUR",
            seller = EInvoiceParty(name = "ACME GmbH"),
            vatBreakdown =
                listOf(
                    EInvoiceVatBreakdown(
                        categoryCode = "S",
                        rate = BigDecimal("19.00"),
                        taxableAmount = BigDecimal("100.0000"),
                        vatAmount = BigDecimal("19.0000"),
                    ),
                ),
        )

    @Test
    fun twoIdenticallyBuiltInvoicesAreEqual() {
        assertEquals(minimal(), minimal())
    }

    @Test
    fun aDifferenceInAChildIsVisibleInEquality() {
        val other = minimal().copy(vatBreakdown = emptyList())
        assertNotEquals(minimal(), other)
    }

    // BigDecimal equality is scale-sensitive: 19.00 != 19.0. The model must therefore never
    // normalize scale on construction, or a round trip that reads "19.0" from one format and
    // "19.00" from another would compare unequal for a reason that is not a real difference.
    @Test
    fun scaleIsPreservedNotNormalized() {
        val twoDp = minimal().vatBreakdown[0].copy(rate = BigDecimal("19.00"))
        val oneDp = twoDp.copy(rate = BigDecimal("19.0"))
        assertNotEquals(twoDp, oneDp) // the model does not collapse 19.00 and 19.0
        assertEquals(2, twoDp.rate.scale()) // and the field holds exactly what was passed in
    }

    @Test
    fun buyerAndPrepaidAmountDefaultToNull() {
        val invoice = minimal()
        assertEquals(null, invoice.buyer)
        assertEquals(null, invoice.prepaidAmount)
    }

    @Test
    fun buyerAndPrepaidAmountAreReadableWhenSet() {
        val buyer =
            EInvoiceParty(
                name = "Kanzlei Maas",
                vatId = "DE111",
                street = "Hafenweg 3",
                zip = "20457",
                city = "Hamburg",
                country = "DE",
            )
        val invoice = minimal().copy(buyer = buyer, prepaidAmount = BigDecimal("30.00"))

        assertEquals(buyer, invoice.buyer)
        assertEquals(BigDecimal("30.00"), invoice.prepaidAmount)

        val copy = invoice.copy()
        assertEquals(invoice, copy)
    }
}
