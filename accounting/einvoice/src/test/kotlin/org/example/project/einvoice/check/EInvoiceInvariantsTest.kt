package org.example.project.einvoice.check

import kotlinx.datetime.LocalDate
import org.example.project.einvoice.model.EInvoice
import org.example.project.einvoice.model.EInvoiceFormat
import org.example.project.einvoice.model.EInvoiceParty
import org.example.project.einvoice.model.EInvoiceVatBreakdown
import org.example.project.einvoice.syntax.CiiInvoiceMapper
import org.example.project.einvoice.syntax.UblInvoiceMapper
import org.example.project.securexml.SecureXml
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [EInvoiceInvariants.check] — a deliberately narrow arithmetic sanity check, not EN 16931
 * rule validation. Amounts are compared by value ([BigDecimal.compareTo]), never by scale, because
 * real invoices do not zero-pad: the corpus fixture writes `336.9`, not `336.90`.
 */
class EInvoiceInvariantsTest {
    private val seller = EInvoiceParty(name = "Seller GmbH")

    private fun invoiceOf(
        vatBreakdown: List<EInvoiceVatBreakdown>,
        taxTotal: BigDecimal? = null,
        taxInclusiveTotal: BigDecimal? = null,
    ) = EInvoice(
        format = EInvoiceFormat.XRECHNUNG,
        invoiceNumber = "INV-1",
        issueDate = LocalDate(2026, 1, 1),
        currency = "EUR",
        seller = seller,
        vatBreakdown = vatBreakdown,
        taxTotal = taxTotal,
        taxInclusiveTotal = taxInclusiveTotal,
    )

    private fun groupOf(
        taxableAmount: String,
        vatAmount: String,
        rate: String = "19",
    ) = EInvoiceVatBreakdown(
        categoryCode = "S",
        rate = BigDecimal(rate),
        taxableAmount = BigDecimal(taxableAmount),
        vatAmount = BigDecimal(vatAmount),
    )

    @Test
    fun `a consistent invoice has no violation`() {
        val invoice =
            invoiceOf(
                vatBreakdown = listOf(groupOf(taxableAmount = "100.00", vatAmount = "19.00")),
                taxTotal = BigDecimal("19.00"),
                taxInclusiveTotal = BigDecimal("119.00"),
            )

        assertNull(EInvoiceInvariants.check(invoice))
    }

    @Test
    fun `a scale difference between the group and the declared total is not a violation`() {
        val invoice =
            invoiceOf(
                vatBreakdown = listOf(groupOf(taxableAmount = "100.00", vatAmount = "19.0000")),
                taxTotal = BigDecimal("19.00"),
                taxInclusiveTotal = BigDecimal("119.0"),
            )

        assertNull(EInvoiceInvariants.check(invoice))
    }

    @Test
    fun `a genuine VAT total mismatch is a violation naming both numbers`() {
        val invoice =
            invoiceOf(
                vatBreakdown = listOf(groupOf(taxableAmount = "100.00", vatAmount = "19.00")),
                taxTotal = BigDecimal("7.00"),
            )

        val violation = EInvoiceInvariants.check(invoice)

        assertNotNull(violation)
        assertTrue(violation.contains("19.00"), violation)
        assertTrue(violation.contains("7.00"), violation)
    }

    @Test
    fun `an absent declared tax total is not checked`() {
        val invoice =
            invoiceOf(
                vatBreakdown = listOf(groupOf(taxableAmount = "100.00", vatAmount = "19.00")),
                taxTotal = null,
            )

        assertNull(EInvoiceInvariants.check(invoice))
    }

    @Test
    fun `a genuine tax-inclusive total mismatch is a violation`() {
        val invoice =
            invoiceOf(
                vatBreakdown = listOf(groupOf(taxableAmount = "100.00", vatAmount = "19.00")),
                taxTotal = BigDecimal("19.00"),
                taxInclusiveTotal = BigDecimal("999.00"),
            )

        val violation = EInvoiceInvariants.check(invoice)

        assertNotNull(violation)
        assertTrue(violation.contains("119.00"), violation)
        assertTrue(violation.contains("999.00"), violation)
    }

    @Test
    fun `an absent declared tax-inclusive total is not checked`() {
        val invoice =
            invoiceOf(
                vatBreakdown = listOf(groupOf(taxableAmount = "100.00", vatAmount = "19.00")),
                taxTotal = BigDecimal("19.00"),
                taxInclusiveTotal = null,
            )

        assertNull(EInvoiceInvariants.check(invoice))
    }

    @Test
    fun `the real CII corpus fixture is arithmetically consistent`() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/corpus/xrechnung-cii.xml")).use { it.readBytes() }
        val invoice = CiiInvoiceMapper.map(SecureXml.parse(bytes))

        assertNull(EInvoiceInvariants.check(invoice))
    }

    @Test
    fun `the real UBL corpus fixture is arithmetically consistent`() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/corpus/xrechnung-ubl.xml")).use { it.readBytes() }
        val invoice = UblInvoiceMapper.map(SecureXml.parse(bytes))

        assertNull(EInvoiceInvariants.check(invoice))
    }
}
