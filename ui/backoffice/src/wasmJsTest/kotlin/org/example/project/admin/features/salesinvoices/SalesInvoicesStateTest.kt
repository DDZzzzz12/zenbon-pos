package org.example.project.admin.features.salesinvoices

import org.example.project.admin.features.salesinvoices.presentation.SalesInvoiceBucket
import org.example.project.admin.features.salesinvoices.presentation.SalesInvoicesState
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SalesInvoicesStateTest {
    private fun invoice(
        status: String,
        documentId: Long? = null,
    ) = SalesInvoiceDto.ResponseDto(
        id = 1L,
        invoiceNumber = if (status == "DRAFT") null else "RG-2027-00001",
        status = status,
        revenueOrigin = "POS_SETTLED",
        invoiceTypeCode = "380",
        precedingInvoiceId = null,
        billId = 7L,
        debtorId = null,
        buyerName = "Musterfirma GmbH",
        buyerVatId = null,
        buyerStreet = null,
        buyerZip = null,
        buyerCity = null,
        buyerCountry = null,
        buyerEmail = null,
        sellerName = null,
        sellerVatId = null,
        sellerTaxNumber = null,
        issueDate = "2027-01-05T10:00",
        serviceDate = "2026-08-16T19:30",
        dueDate = null,
        currency = "EUR",
        lineNetTotal = null,
        taxTotal = null,
        taxInclusiveTotal = "28.3000",
        prepaidAmount = null,
        amountDue = null,
        openAmount = null,
        notes = null,
        paymentTermsText = null,
        revenueAccountNo = null,
        buCode = null,
        documentId = documentId,
        issuedAt = null,
        cancelledAt = null,
        lines = emptyList(),
        vatBreakdown = emptyList(),
    )

    @Test
    fun `the buckets filter the way an operator reads them`() {
        assertEquals("DRAFT" to null, SalesInvoiceBucket.DRAFTS.status to SalesInvoiceBucket.DRAFTS.typeCode)
        assertEquals(
            "ISSUED" to "380",
            SalesInvoiceBucket.ISSUED.status to SalesInvoiceBucket.ISSUED.typeCode,
            "Ausgestellt must exclude Gutschriften, which are not sales",
        )
        assertEquals(null to "381", SalesInvoiceBucket.CREDIT_NOTES.status to SalesInvoiceBucket.CREDIT_NOTES.typeCode)
        assertEquals("CANCELLED" to null, SalesInvoiceBucket.CANCELLED.status to SalesInvoiceBucket.CANCELLED.typeCode)
    }

    @Test
    fun `the open and overdue buckets filter the way an operator reads them`() {
        assertEquals("ISSUED" to "380", SalesInvoiceBucket.OPEN.status to SalesInvoiceBucket.OPEN.typeCode)
        assertTrue(SalesInvoiceBucket.OPEN.open, "Offen is open issued rows, not every issued row")
        assertFalse(SalesInvoiceBucket.OPEN.overdue)
        assertEquals("ISSUED" to "380", SalesInvoiceBucket.OVERDUE.status to SalesInvoiceBucket.OVERDUE.typeCode)
        assertTrue(SalesInvoiceBucket.OVERDUE.open, "Überfällig is the open rows that are also past due")
        assertTrue(SalesInvoiceBucket.OVERDUE.overdue)
    }

    @Test
    fun `only a draft can be issued`() {
        assertTrue(SalesInvoicesState(selected = invoice("DRAFT")).canIssueSelected)
        assertFalse(SalesInvoicesState(selected = invoice("ISSUED")).canIssueSelected)
        assertFalse(SalesInvoicesState(selected = invoice("CANCELLED")).canIssueSelected)
        assertFalse(SalesInvoicesState(selected = null).canIssueSelected, "nothing selected, nothing to issue")
    }

    @Test
    fun `only an issued invoice can be cancelled`() {
        assertTrue(SalesInvoicesState(selected = invoice("ISSUED")).canCancelSelected)
        assertFalse(
            SalesInvoicesState(selected = invoice("DRAFT")).canCancelSelected,
            "a draft consumed no number, so it is deleted rather than reversed",
        )
        assertFalse(SalesInvoicesState(selected = invoice("CANCELLED")).canCancelSelected)
    }

    @Test
    fun `a mutation in flight disables both actions`() {
        val busy = SalesInvoicesState(selected = invoice("DRAFT"), isMutating = true)
        assertFalse(busy.canIssueSelected, "double-issuing is what the §14 sequence cannot survive")
        assertFalse(SalesInvoicesState(selected = invoice("ISSUED"), isMutating = true).canCancelSelected)
    }

    @Test
    fun `the PDF action needs an archived document`() {
        assertTrue(SalesInvoicesState(selected = invoice("ISSUED", documentId = 42L)).canOpenPdf)
        assertFalse(SalesInvoicesState(selected = invoice("DRAFT")).canOpenPdf, "a draft has nothing archived")
    }
}
