package org.example.project.features.salesinvoice

import org.example.project.core.error.PosErrorCode
import org.example.project.features.salesinvoice.domain.service.SalesInvoiceAllowanceContent
import org.example.project.features.salesinvoice.domain.service.SalesInvoiceContent
import org.example.project.features.salesinvoice.domain.service.SalesInvoiceLineContent
import org.example.project.features.salesinvoice.domain.service.SalesInvoiceValidator
import org.example.project.features.salesinvoice.domain.service.SalesInvoiceVatContent
import org.example.project.infrastructure.errors.PosException
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SalesInvoiceValidatorTest {
    private fun line(
        net: String = "31.8400",
        vat: String = "6.0500",
        gross: String = "37.8900",
    ) = SalesInvoiceLineContent(
        netAmount = BigDecimal(net),
        vatAmount = BigDecimal(vat),
        grossAmount = BigDecimal(gross),
        vatRate = BigDecimal("19.00"),
    )

    private fun vat(
        net: String = "31.8400",
        vat: String = "6.0500",
    ) = SalesInvoiceVatContent(
        vatRate = BigDecimal("19.00"),
        taxableAmount = BigDecimal(net),
        vatAmount = BigDecimal(vat),
    )

    private fun content(
        buyerName: String = "Musterfirma GmbH",
        hasServiceDate: Boolean = true,
        lines: List<SalesInvoiceLineContent> = listOf(line()),
        vatRows: List<SalesInvoiceVatContent> = listOf(vat()),
        taxInclusiveTotal: String = "37.8900",
        taxTotal: String = "6.0500",
    ) = SalesInvoiceContent(
        buyerName = buyerName,
        hasServiceDate = hasServiceDate,
        lines = lines,
        vatRows = vatRows,
        taxInclusiveTotal = BigDecimal(taxInclusiveTotal),
        taxTotal = BigDecimal(taxTotal),
    )

    @Test
    fun `a complete, consistent invoice validates`() {
        SalesInvoiceValidator.requireIssuable(content())
        Unit
    }

    @Test
    fun `a missing buyer name is refused`() {
        val error = assertFailsWith<PosException> { SalesInvoiceValidator.requireIssuable(content(buyerName = "  ")) }
        assertEquals(PosErrorCode.SALES_INVOICE_NOT_ISSUABLE, error.code)
        Unit
    }

    @Test
    fun `a missing Leistungszeitpunkt is refused`() {
        val error =
            assertFailsWith<PosException> { SalesInvoiceValidator.requireIssuable(content(hasServiceDate = false)) }
        assertEquals(PosErrorCode.SALES_INVOICE_NOT_ISSUABLE, error.code)
        Unit
    }

    @Test
    fun `an invoice with no lines is refused`() {
        val error = assertFailsWith<PosException> { SalesInvoiceValidator.requireIssuable(content(lines = emptyList())) }
        assertEquals(PosErrorCode.SALES_INVOICE_NOT_ISSUABLE, error.code)
        Unit
    }

    @Test
    fun `a line whose net plus vat is not its gross is refused`() {
        val error =
            assertFailsWith<PosException> {
                SalesInvoiceValidator.requireIssuable(
                    content(lines = listOf(line(net = "31.8400", vat = "6.0500", gross = "37.9000"))),
                )
            }
        assertEquals(PosErrorCode.SALES_INVOICE_NOT_ISSUABLE, error.code)
        Unit
    }

    @Test
    fun `a vat breakdown that does not sum to the tax total is refused`() {
        val error =
            assertFailsWith<PosException> {
                SalesInvoiceValidator.requireIssuable(content(taxTotal = "6.0600"))
            }
        assertEquals(PosErrorCode.SALES_INVOICE_NOT_ISSUABLE, error.code)
        Unit
    }

    @Test
    fun `lines that do not sum to the invoice total are refused`() {
        val error =
            assertFailsWith<PosException> {
                SalesInvoiceValidator.requireIssuable(content(taxInclusiveTotal = "40.0000"))
            }
        assertEquals(PosErrorCode.SALES_INVOICE_NOT_ISSUABLE, error.code)
        Unit
    }

    @Test
    fun `scale differences are not treated as disagreements`() {
        SalesInvoiceValidator.requireIssuable(content(taxInclusiveTotal = "37.89", taxTotal = "6.05"))
        Unit
    }

    // ---- Document-level allowances (BG-20) ---------------------------------------------------------

    private fun allowance(
        net: String = "2.38",
        vat: String = "0.45",
        gross: String = "2.83",
        rate: String = "19.00",
    ) = SalesInvoiceAllowanceContent(
        isCharge = false,
        amount = BigDecimal(net),
        vatAmount = BigDecimal(vat),
        grossAmount = BigDecimal(gross),
        vatRate = BigDecimal(rate),
    )

    /** 28.30 at 19 % less a 2.83 promotion: the bill row 21.40 + 4.07 = 25.47. */
    private fun promoted(
        allowances: List<SalesInvoiceAllowanceContent> = listOf(allowance()),
        vatRows: List<SalesInvoiceVatContent> = listOf(vat(net = "21.40", vat = "4.07")),
        allowanceTotal: String = "2.38",
        lineNetTotal: String = "23.78",
        taxExclusiveTotal: String = "21.40",
        taxInclusiveTotal: String = "25.47",
    ) = content(
        lines = listOf(line(net = "23.7815", vat = "4.5185", gross = "28.30")),
        vatRows = vatRows,
        taxInclusiveTotal = taxInclusiveTotal,
        taxTotal = vatRows.fold(BigDecimal.ZERO) { acc, r -> acc + r.vatAmount }.toPlainString(),
    ).copy(
        allowances = allowances,
        allowanceTotal = BigDecimal(allowanceTotal),
        lineNetTotal = BigDecimal(lineNetTotal),
        taxExclusiveTotal = BigDecimal(taxExclusiveTotal),
    )

    @Test
    fun `an invoice whose allowance the VAT breakdown absorbed validates`() {
        SalesInvoiceValidator.requireIssuable(promoted())
        Unit
    }

    @Test
    fun `an allowance whose net plus VAT is not its gross is refused`() {
        val error = assertFailsWith<PosException> { SalesInvoiceValidator.requireIssuable(promoted(allowances = listOf(allowance(vat = "0.46")))) }
        assertEquals(PosErrorCode.SALES_INVOICE_NOT_ISSUABLE, error.code)
        Unit
    }

    @Test
    fun `allowances that do not sum to BT-107 are refused`() {
        val error = assertFailsWith<PosException> { SalesInvoiceValidator.requireIssuable(promoted(allowanceTotal = "2.39", lineNetTotal = "23.79")) }
        assertEquals(PosErrorCode.SALES_INVOICE_NOT_ISSUABLE, error.code)
        Unit
    }

    @Test
    fun `a tax-exclusive total that is not BT-106 less BT-107 is refused`() {
        val error = assertFailsWith<PosException> { SalesInvoiceValidator.requireIssuable(promoted(lineNetTotal = "23.80")) }
        assertEquals(PosErrorCode.SALES_INVOICE_NOT_ISSUABLE, error.code)
        Unit
    }

    @Test
    fun `an allowance at a rate whose VAT group did not absorb it is refused`() {
        // Same money, but the allowance claims 7 %: the 19 % group no longer equals its lines less its allowances.
        val error =
            assertFailsWith<PosException> {
                SalesInvoiceValidator.requireIssuable(promoted(allowances = listOf(allowance(rate = "7.00"))))
            }
        assertEquals(PosErrorCode.SALES_INVOICE_NOT_ISSUABLE, error.code)
        kotlin.test.assertTrue(error.message!!.contains("VAT group"), error.message)
        Unit
    }

    @Test
    fun `lines without their allowance do not reach the invoice total`() {
        val error = assertFailsWith<PosException> { SalesInvoiceValidator.requireIssuable(promoted(allowances = emptyList(), allowanceTotal = "0")) }
        assertEquals(PosErrorCode.SALES_INVOICE_NOT_ISSUABLE, error.code)
        Unit
    }
}
