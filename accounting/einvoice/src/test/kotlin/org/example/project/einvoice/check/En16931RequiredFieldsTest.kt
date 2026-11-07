package org.example.project.einvoice.check

import kotlinx.datetime.LocalDate
import org.example.project.einvoice.model.EInvoice
import org.example.project.einvoice.model.EInvoiceFormat
import org.example.project.einvoice.model.EInvoiceLine
import org.example.project.einvoice.model.EInvoiceParty
import org.example.project.einvoice.model.EInvoiceVatBreakdown
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [En16931RequiredFields] is the pre-issue gate: for each field EN 16931 requires that this
 * project's CII writer needs, a missing value must produce a non-null message naming it, and a
 * complete invoice must pass silently.
 */
class En16931RequiredFieldsTest {
    private val sample =
        EInvoice(
            format = EInvoiceFormat.ZUGFERD,
            invoiceNumber = "RG-2026-00001",
            issueDate = LocalDate(2026, 8, 18),
            currency = "EUR",
            seller =
                EInvoiceParty(
                    name = "Muster GmbH",
                    vatId = "DE123456789",
                    taxNumber = "12/345/67890",
                    street = "Musterstraße 1",
                    zip = "10115",
                    city = "Berlin",
                    country = "DE",
                ),
            buyer =
                EInvoiceParty(
                    name = "Kanzlei Maas",
                    vatId = "DE999999999",
                    street = "Anwaltsweg 5",
                    zip = "80331",
                    city = "München",
                    country = "DE",
                ),
            vatBreakdown =
                listOf(
                    EInvoiceVatBreakdown(
                        categoryCode = "S",
                        rate = BigDecimal("19.00"),
                        taxableAmount = BigDecimal("100.00"),
                        vatAmount = BigDecimal("19.00"),
                    ),
                ),
            lines =
                listOf(
                    EInvoiceLine(
                        lineId = "1",
                        itemName = "Beratung",
                        quantity = BigDecimal("1"),
                        unitCode = "HUR",
                        netUnitPrice = BigDecimal("100.00"),
                        lineNetAmount = BigDecimal("100.00"),
                        vatCategoryCode = "S",
                        vatRate = BigDecimal("19.00"),
                    ),
                ),
            invoiceTypeCode = "380",
        )

    @Test
    fun `happy path returns null`() {
        assertNull(En16931RequiredFields.check(sample))
    }

    @Test
    fun `blank seller name is rejected`() {
        val invoice = sample.copy(seller = sample.seller.copy(name = "  "))
        assertNotNull(En16931RequiredFields.check(invoice))
    }

    @Test
    fun `seller with neither vatId nor taxNumber is rejected`() {
        val invoice = sample.copy(seller = sample.seller.copy(vatId = null, taxNumber = null))
        assertNotNull(En16931RequiredFields.check(invoice))
    }

    @Test
    fun `missing buyer is rejected`() {
        val invoice = sample.copy(buyer = null)
        assertNotNull(En16931RequiredFields.check(invoice))
    }

    @Test
    fun `blank buyer name is rejected`() {
        val invoice = sample.copy(buyer = sample.buyer!!.copy(name = " "))
        assertNotNull(En16931RequiredFields.check(invoice))
    }

    @Test
    fun `buyer missing street is rejected`() {
        val invoice = sample.copy(buyer = sample.buyer!!.copy(street = null))
        assertNotNull(En16931RequiredFields.check(invoice))
    }

    @Test
    fun `buyer missing zip is rejected`() {
        val invoice = sample.copy(buyer = sample.buyer!!.copy(zip = null))
        assertNotNull(En16931RequiredFields.check(invoice))
    }

    @Test
    fun `buyer missing city is rejected`() {
        val invoice = sample.copy(buyer = sample.buyer!!.copy(city = null))
        assertNotNull(En16931RequiredFields.check(invoice))
    }

    @Test
    fun `empty lines is rejected`() {
        val invoice = sample.copy(lines = emptyList())
        assertNotNull(En16931RequiredFields.check(invoice))
    }

    @Test
    fun `empty vatBreakdown is rejected`() {
        val invoice = sample.copy(vatBreakdown = emptyList())
        assertNotNull(En16931RequiredFields.check(invoice))
    }

    @Test
    fun `blank invoiceNumber is rejected`() {
        val invoice = sample.copy(invoiceNumber = " ")
        assertNotNull(En16931RequiredFields.check(invoice))
    }

    @Test
    fun `blank currency is rejected`() {
        val invoice = sample.copy(currency = "")
        assertNotNull(En16931RequiredFields.check(invoice))
    }

    @Test
    fun `null invoiceTypeCode is rejected`() {
        val invoice = sample.copy(invoiceTypeCode = null)
        assertNotNull(En16931RequiredFields.check(invoice))
    }

    @Test
    fun `a free-text seller country is rejected - BT-40 must be ISO 3166-1 alpha-2`() {
        val message = En16931RequiredFields.check(sample.copy(seller = sample.seller.copy(country = "Germany")))
        assertNotNull(message)
        assertTrue("BT-40" in message && "Germany" in message, message)
    }

    @Test
    fun `a missing seller or buyer country is rejected (BR-09, BR-11)`() {
        assertNotNull(En16931RequiredFields.check(sample.copy(seller = sample.seller.copy(country = null))))
        assertNotNull(En16931RequiredFields.check(sample.copy(buyer = sample.buyer!!.copy(country = " "))))
        assertNotNull(En16931RequiredFields.check(sample.copy(buyer = sample.buyer!!.copy(country = "de"))))
    }
}
