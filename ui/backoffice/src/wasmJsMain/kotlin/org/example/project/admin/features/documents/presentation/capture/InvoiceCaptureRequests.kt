package org.example.project.admin.features.documents.presentation.capture

import org.example.project.admin.core.presentation.format.editableDecimalText
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.ClearableBookingField
import org.example.project.core.utils.toCanonicalMoneyOrNull
import org.example.project.money.Decimal

/** Seeds the form from a loaded aggregate. A null booking is a clean "not yet booked" state. */
internal fun DocumentDto.InvoiceDto.toForm(): InvoiceCaptureForm =
    InvoiceCaptureForm(
        invoiceNumber = invoiceNumber.orEmpty(),
        issueDate = issueDate.orEmpty().substringBefore('T'),
        grossTotal = editableDecimalText(taxInclusiveTotal.orEmpty(), 2),
        sellerName = sellerName.orEmpty(),
        invoiceTypeCode = invoiceTypeCode,
        precedingInvoiceReference = precedingInvoiceReference.orEmpty(),
        vatRows =
            vatBreakdown
                .map { row ->
                    VatRowForm(
                        rate = editableDecimalText(row.vatRate, 2),
                        gross = editableDecimalText(row.taxableAmount.plusOrBlank(row.vatAmount), 2),
                        description = row.description.orEmpty(),
                    )
                }.ifEmpty { listOf(VatRowForm()) },
        bpAccountNo = booking?.bpAccountNo.orEmpty(),
        accountNo = booking?.accountNo.orEmpty(),
        bookingText = booking?.bookingText.orEmpty(),
    )

/**
 * The **seed-and-overwrite** mapper — the reason this task exists.
 *
 * `InvoiceContentRequestDto` is a documented full replace: an omitted field is written as `null`.
 * The form renders eight supplier fields; the aggregate holds roughly twenty-eight. So the request is
 * built from the **loaded invoice** and only the form-owned fields are overwritten. A caller that
 * constructs the DTO field-by-field from the form instead silently wipes the seller's address, VAT
 * id, buyer reference, payment terms and subtotals on every save — regression C1, in a new place.
 *
 * The receiver is nullable: `null` is the create case, where there is nothing to preserve.
 *
 * [DocumentDto.InvoiceContentRequestDto.vatBreakdown] is tri-state on the wire (`null` = leave, `[]` =
 * delete all, list = replace). The form always owns the breakdown, so a list is always sent — the
 * server replaces the whole child set, never merges.
 */
internal fun DocumentDto.InvoiceDto?.toContentRequest(form: InvoiceCaptureForm): DocumentDto.InvoiceContentRequestDto =
    DocumentDto.InvoiceContentRequestDto(
        // ---- form-owned ----
        invoiceNumber = form.invoiceNumber.trim().ifBlank { null },
        issueDate = form.issueDate.trim().ifBlank { null },
        invoiceTypeCode = form.invoiceTypeCode,
        precedingInvoiceReference = form.precedingInvoiceReference.trim().ifBlank { null },
        sellerName = form.sellerName.trim().ifBlank { null },
        taxInclusiveTotal = form.grossTotal.toCanonicalMoneyOrNull(),
        vatBreakdown = form.toVatBreakdown(),
        // ---- carried forward from the loaded aggregate ----
        dueDate = this?.dueDate,
        deliveryDate = this?.deliveryDate,
        currency = this?.currency ?: "EUR",
        buyerReference = this?.buyerReference,
        sellerVatId = this?.sellerVatId,
        sellerTaxNumber = this?.sellerTaxNumber,
        sellerStreet = this?.sellerStreet,
        sellerZip = this?.sellerZip,
        sellerCity = this?.sellerCity,
        sellerCountry = this?.sellerCountry,
        sellerPhone = this?.sellerPhone,
        sellerEmail = this?.sellerEmail,
        paymentIban = this?.paymentIban,
        paymentReference = this?.paymentReference,
        paymentTermsText = this?.paymentTermsText,
        lineNetTotal = this?.lineNetTotal,
        taxExclusiveTotal = this?.taxExclusiveTotal,
        taxTotal = this?.taxTotal,
        amountDue = this?.amountDue,
    )

/**
 * Booking, with clearing said out loud.
 *
 * `null` on the booking request means "leave unchanged" — a deliberate contract pinned by two server
 * tests — so a field the operator emptied has to be named in `clearFields`, or the old value survives
 * and the operator is told nothing. [pristine] is the snapshot the form was seeded from; only a field
 * that **had** a value and now has none is named.
 */
internal fun InvoiceCaptureForm.toBookingRequest(pristine: InvoiceCaptureForm): DocumentDto.UpdateInvoiceBookingRequestDto {
    val cleared =
        buildList {
            if (bpAccountNo.isBlank() && pristine.bpAccountNo.isNotBlank()) add(ClearableBookingField.BP_ACCOUNT_NO)
            if (accountNo.isBlank() && pristine.accountNo.isNotBlank()) add(ClearableBookingField.ACCOUNT_NO)
            if (bookingText.isBlank() && pristine.bookingText.isNotBlank()) add(ClearableBookingField.BOOKING_TEXT)
        }
    return DocumentDto.UpdateInvoiceBookingRequestDto(
        bpAccountNo = bpAccountNo.trim().ifBlank { null },
        accountNo = accountNo.trim().ifBlank { null },
        bookingText = bookingText.trim().ifBlank { null },
        clearFields = cleared.ifEmpty { null },
    )
}

/**
 * The breakdown the form owns, derived row by row.
 *
 * A row whose rate or gross will not parse is **dropped**, not sent as zeros — [validateCapture]
 * blocks a save in that state anyway, and a zeroed row on the wire would look like a deliberate 0 %
 * assertion. A row's [VatRowForm.description] becomes that entry's `<bookingText>` in the export,
 * overriding the invoice-level one.
 */
internal fun InvoiceCaptureForm.toVatBreakdown(): List<DocumentDto.VatBreakdownDto> =
    vatRows.mapIndexedNotNull { index, row ->
        deriveVatRow(rate = row.rate, gross = grossFor(index))
            ?.copy(description = row.description.trim().ifBlank { null })
    }

/** `taxable + vat` as a display string, or blank when either leg is unparseable. */
private fun String.plusOrBlank(other: String): String {
    val a = Decimal.parseOrNull(this) ?: return ""
    val b = Decimal.parseOrNull(other) ?: return ""
    return (a + b).toPlainString()
}
