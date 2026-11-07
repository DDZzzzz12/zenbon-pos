package org.example.project.admin.features.documents.capture

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.documents.presentation.capture.InvoiceCaptureField
import org.example.project.admin.features.documents.presentation.capture.InvoiceCaptureForm
import org.example.project.admin.features.documents.presentation.capture.VatRowForm
import org.example.project.admin.features.documents.presentation.capture.captureWarnings
import org.example.project.admin.features.documents.presentation.capture.validateCapture
import org.example.project.admin.features.documents.presentation.capture.vatRowErrors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The spec is blunt about why these are tests and not just code: *"Form validation is the only thing
 * between an operator and a rejected DATEV import. The server enforces max lengths but none of the
 * numeric or pattern rules … These rules must be tested, not merely implemented."*
 */
class InvoiceCaptureValidationTest {
    private fun valid(): InvoiceCaptureForm =
        InvoiceCaptureForm(
            invoiceNumber = "RE-2026-0001",
            issueDate = "2026-03-01",
            grossTotal = "238,00",
            sellerName = "Lieferant GmbH",
            vatRows = listOf(VatRowForm(rate = "19")),
        )

    @Test
    fun `a complete form has no errors`() {
        assertEquals(emptyMap(), validateCapture(valid()))
        assertEquals(emptyMap(), vatRowErrors(valid()))
    }

    @Test
    fun `each of the five required fields reports itself by name`() {
        assertEquals(
            AdminStringKeys.InvoiceCaptureErrorRequired,
            validateCapture(valid().copy(invoiceNumber = " "))[InvoiceCaptureField.INVOICE_NUMBER],
        )
        assertEquals(
            AdminStringKeys.InvoiceCaptureErrorRequired,
            validateCapture(valid().copy(issueDate = ""))[InvoiceCaptureField.ISSUE_DATE],
        )
        assertEquals(
            AdminStringKeys.InvoiceCaptureErrorRequired,
            validateCapture(valid().copy(grossTotal = ""))[InvoiceCaptureField.GROSS_TOTAL],
        )
        assertEquals(
            AdminStringKeys.InvoiceCaptureErrorRequired,
            validateCapture(valid().copy(sellerName = ""))[InvoiceCaptureField.SELLER_NAME],
        )
        assertEquals(
            AdminStringKeys.InvoiceCaptureErrorRequired,
            vatRowErrors(valid().copy(vatRows = listOf(VatRowForm(rate = ""))))[0],
        )
    }

    @Test
    fun `an invoice number over 36 characters is rejected`() {
        val errors = validateCapture(valid().copy(invoiceNumber = "R".repeat(37)))
        assertEquals(
            AdminStringKeys.InvoiceCaptureErrorInvoiceNumberTooLong,
            errors[InvoiceCaptureField.INVOICE_NUMBER],
        )
        assertTrue(validateCapture(valid().copy(invoiceNumber = "R".repeat(36))).isEmpty())
    }

    @Test
    fun `a date that is not yyyy-MM-dd is rejected, including an impossible one`() {
        assertEquals(
            AdminStringKeys.InvoiceCaptureErrorDateFormat,
            validateCapture(valid().copy(issueDate = "01.03.2026"))[InvoiceCaptureField.ISSUE_DATE],
        )
        assertEquals(
            AdminStringKeys.InvoiceCaptureErrorDateFormat,
            validateCapture(valid().copy(issueDate = "2026-13-45"))[InvoiceCaptureField.ISSUE_DATE],
            "a regex alone would accept this",
        )
    }

    @Test
    fun `an unparseable gross amount is rejected but zero is not`() {
        assertEquals(
            AdminStringKeys.DocumentInvalidAmount,
            validateCapture(valid().copy(grossTotal = "zwei euro"))[InvoiceCaptureField.GROSS_TOTAL],
        )
        assertTrue(
            validateCapture(valid().copy(grossTotal = "0,00")).isEmpty(),
            "a Nullrechnung is a real document and must be recordable",
        )
    }

    @Test
    fun `a credit note requires a reference to the invoice it credits`() {
        val gutschrift = valid().copy(invoiceTypeCode = "381")
        assertEquals(
            AdminStringKeys.InvoiceCaptureErrorPrecedingRequired,
            validateCapture(gutschrift)[InvoiceCaptureField.PRECEDING_REFERENCE],
        )
        assertTrue(validateCapture(gutschrift.copy(precedingInvoiceReference = "RE-9")).isEmpty())
        assertTrue(
            validateCapture(valid().copy(invoiceTypeCode = "380")).isEmpty(),
            "an ordinary invoice must not demand a reference",
        )
    }

    @Test
    fun `multi-rate rows must add up to the gross total`() {
        val mixed =
            valid().copy(
                grossTotal = "238,00",
                vatRows = listOf(VatRowForm(rate = "19", gross = "119,00"), VatRowForm(rate = "7", gross = "100,00")),
            )
        assertEquals(
            AdminStringKeys.InvoiceCaptureErrorVatSumMismatch,
            validateCapture(mixed)[InvoiceCaptureField.VAT_ROW_SUM],
            "DATEV rejects the whole import on a mismatch; the server only warns",
        )
        val balanced =
            mixed.copy(
                vatRows = listOf(VatRowForm(rate = "19", gross = "119,00"), VatRowForm(rate = "7", gross = "119,00")),
            )
        assertTrue(validateCapture(balanced).isEmpty())
    }

    @Test
    fun `the two booking accounts are range-checked and the booking text is length-checked`() {
        assertEquals(
            AdminStringKeys.InvoiceCaptureErrorBpAccountRange,
            validateCapture(valid().copy(bpAccountNo = "999"))[InvoiceCaptureField.BP_ACCOUNT_NO],
        )
        assertEquals(
            AdminStringKeys.InvoiceCaptureErrorAccountRange,
            validateCapture(valid().copy(accountNo = "0"))[InvoiceCaptureField.ACCOUNT_NO],
        )
        assertEquals(
            AdminStringKeys.InvoiceCaptureErrorBookingTextTooLong,
            validateCapture(valid().copy(bookingText = "B".repeat(61)))[InvoiceCaptureField.BOOKING_TEXT],
        )
        assertTrue(
            validateCapture(
                valid().copy(bpAccountNo = "70001", accountNo = "4980", bookingText = "Wareneinkauf"),
            ).isEmpty(),
        )
    }

    @Test
    fun `warnings never block and cover the three the spec names`() {
        assertTrue(AdminStringKeys.InvoiceCaptureWarnZeroAmount in captureWarnings(valid().copy(grossTotal = "0,00")))
        assertTrue(
            AdminStringKeys.InvoiceCaptureWarnNoVatRate in
                captureWarnings(valid().copy(vatRows = listOf(VatRowForm(rate = "0")))),
        )
        assertTrue(
            AdminStringKeys.InvoiceCaptureWarnStrippedCharacters in
                captureWarnings(valid().copy(invoiceNumber = "RE 2026#1")),
        )
        assertTrue(captureWarnings(valid()).isEmpty())
        assertTrue(
            validateCapture(valid().copy(invoiceNumber = "RE 2026#1", grossTotal = "0,00")).isEmpty(),
            "a warning must not become an error",
        )
    }
}
