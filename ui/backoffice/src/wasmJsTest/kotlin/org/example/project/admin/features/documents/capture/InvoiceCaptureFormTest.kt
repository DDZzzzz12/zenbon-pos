package org.example.project.admin.features.documents.capture

import org.example.project.admin.features.documents.presentation.capture.InvoiceCaptureForm
import org.example.project.admin.features.documents.presentation.capture.VatRowForm
import org.example.project.admin.features.documents.presentation.capture.toBookingRequest
import org.example.project.admin.features.documents.presentation.capture.toContentRequest
import org.example.project.admin.features.documents.presentation.capture.toForm
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.ClearableBookingField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `InvoiceContentRequestDto` is a **full replace**: a request that omits a field nulls that column.
 * The capture form renders eight of the roughly twenty-eight supplier fields, so a naive
 * `InvoiceContentRequestDto(invoiceNumber = …, sellerName = …)` would wipe the seller's address, VAT
 * id, payment terms, buyer reference and the three subtotal columns on every save.
 *
 * That is not hypothetical: the legacy editor shipped exactly this defect (regression C1). Its form
 * and the seed-from-server workaround it grew were both deleted in Stage 4, but the hazard itself is
 * not gone. Stage 5 deleted `PUT /{id}/invoice-metadata`, its service method and its request DTO, so
 * the legacy full-replace is gone with them; the hazard now lives only in
 * `createDocumentInvoice`/`updateInvoiceContent`, the normalized path this test guards.
 */
class InvoiceCaptureFormTest {
    private fun parsedInvoice(): DocumentDto.InvoiceDto =
        DocumentDto.InvoiceDto(
            id = 7L,
            sourceDocumentId = 3L,
            eInvoiceFormat = "ubl",
            extractionSource = "EINVOICE_XML",
            invoiceNumber = "RE-2026-0001",
            issueDate = "2026-03-01T00:00",
            dueDate = "2026-03-15T00:00",
            currency = "EUR",
            invoiceTypeCode = "380",
            buyerReference = "BR-99",
            sellerName = "Lieferant GmbH",
            sellerVatId = "DE123456789",
            sellerStreet = "Hauptstr. 1",
            sellerZip = "10115",
            sellerCity = "Berlin",
            sellerCountry = "DE",
            paymentIban = "DE02120300000000202051",
            paymentReference = "VZ-1",
            paymentTermsText = "14 Tage netto",
            lineNetTotal = "200.00",
            taxExclusiveTotal = "200.00",
            taxTotal = "38.00",
            taxInclusiveTotal = "238.00",
            amountDue = "238.00",
            vatBreakdown =
                listOf(
                    DocumentDto.VatBreakdownDto(
                        vatCategoryCode = "S",
                        vatRate = "19.00",
                        taxableAmount = "200.00",
                        vatAmount = "38.00",
                    ),
                ),
        )

    @Test
    fun `a save seeded from the loaded invoice preserves every field the form does not render`() {
        val loaded = parsedInvoice()
        val form = loaded.toForm().copy(bookingText = "Wareneinkauf")

        val request = loaded.toContentRequest(form)

        assertEquals("BR-99", request.buyerReference, "an unrendered field must round-trip")
        assertEquals("DE123456789", request.sellerVatId)
        assertEquals("Hauptstr. 1", request.sellerStreet)
        assertEquals("10115", request.sellerZip)
        assertEquals("Berlin", request.sellerCity)
        assertEquals("DE", request.sellerCountry)
        assertEquals("DE02120300000000202051", request.paymentIban)
        assertEquals("VZ-1", request.paymentReference)
        assertEquals("14 Tage netto", request.paymentTermsText)
        assertEquals("200.00", request.lineNetTotal)
        assertEquals("2026-03-15T00:00", request.dueDate)
        assertEquals("EUR", request.currency)
    }

    @Test
    fun `a save with no loaded invoice sends only what the form carries`() {
        val form =
            InvoiceCaptureForm(
                invoiceNumber = "RE-1",
                issueDate = "2026-03-01",
                grossTotal = "119,00",
                sellerName = "Neu GmbH",
                vatRows = listOf(VatRowForm(rate = "19")),
            )

        val request = null.toContentRequest(form)

        assertEquals("RE-1", request.invoiceNumber)
        assertEquals("2026-03-01", request.issueDate)
        assertEquals("119.00", request.taxInclusiveTotal, "money is canonicalized, never a Double")
        assertEquals("Neu GmbH", request.sellerName)
        assertEquals("EUR", request.currency)
        assertNull(request.buyerReference)
        assertEquals(1, request.vatBreakdown?.size)
    }

    @Test
    fun `clearing a booking field that had a value names it in clearFields`() {
        val pristine = InvoiceCaptureForm(bpAccountNo = "70001", bookingText = "Alt", accountNo = "4980")
        val edited = pristine.copy(bookingText = "", accountNo = "5400")

        val request = edited.toBookingRequest(pristine)

        assertEquals("5400", request.accountNo)
        assertNull(request.bookingText, "a cleared field is named, not sent as an empty string")
        assertTrue(ClearableBookingField.BOOKING_TEXT in request.clearFields.orEmpty())
        assertTrue(ClearableBookingField.ACCOUNT_NO !in request.clearFields.orEmpty())
        assertTrue(ClearableBookingField.BP_ACCOUNT_NO !in request.clearFields.orEmpty())
    }
}
