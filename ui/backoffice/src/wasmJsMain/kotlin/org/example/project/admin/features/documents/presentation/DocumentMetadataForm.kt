package org.example.project.admin.features.documents.presentation

import org.example.project.admin.core.presentation.format.editableDecimalText
import org.example.project.core.domain.dto.document.DocumentDto

/**
 * Flat, editable form for a document's metadata. Mirrors the union of [DocumentDto.InvoiceMetadataDto]
 * and [DocumentDto.BankStatementMetadataDto]'s display-relevant fields, all as plain `String` so a
 * text field can bind directly — money fields are validated back to a canonical decimal string on
 * save via `String.toCanonicalMoneyOrNull()`, never parsed into a `Double`.
 *
 * [description] has no backing field on [DocumentDto.DocumentMetadataDto] or [DocumentDto.DocumentResponseDto] —
 * the server only accepts it at upload time ([DocumentDto.UploadDocumentRequestDto.description]) and
 * never returns it. It stays here (defaulting to `""`) for forward compatibility with a future
 * description field, but [toForm] cannot populate it from anything today.
 */
data class DocumentMetadataForm(
    val description: String = "",
    val vendorName: String = "",
    val invoiceNumber: String = "",
    val invoiceDate: String = "",
    val dueDate: String = "",
    val netAmount: String = "",
    val grossAmount: String = "",
    val taxAmount: String = "",
    val currency: String = "",
    val iban: String = "",
    val paymentReference: String = "",
    val bankName: String = "",
    val bic: String = "",
    val accountNumber: String = "",
    val statementNumber: String = "",
    val statementDate: String = "",
    val periodStart: String = "",
    val periodEnd: String = "",
    val openingBalance: String = "",
    val closingBalance: String = "",
)

/**
 * Flattens the DTO's two optional metadata sub-objects into the flat editable form. Both sub-objects
 * are null-safe via `.orEmpty()`, so a `null` field from the server and a field the user typed into
 * then cleared both settle on `""` — [DocumentDetailState.isDirty] never gets stuck comparing `null`
 * against `""`.
 */
fun DocumentDto.DocumentMetadataDto.toForm(): DocumentMetadataForm {
    val invoice = invoiceMetadata
    val bank = bankStatementMetadata
    return DocumentMetadataForm(
        vendorName = invoice?.vendorName.orEmpty(),
        invoiceNumber = invoice?.invoiceNumber.orEmpty(),
        invoiceDate = invoice?.invoiceDate.orEmpty(),
        dueDate = invoice?.dueDate.orEmpty(),
        netAmount = editableDecimalText(invoice?.netAmount.orEmpty(), 2),
        grossAmount = editableDecimalText(invoice?.grossAmount.orEmpty(), 2),
        taxAmount = editableDecimalText(invoice?.taxAmount.orEmpty(), 2),
        currency = invoice?.currency.orEmpty(),
        iban = invoice?.iban.orEmpty(),
        paymentReference = invoice?.paymentReference.orEmpty(),
        bankName = bank?.bankName.orEmpty(),
        bic = bank?.bic.orEmpty(),
        accountNumber = bank?.accountNumber.orEmpty(),
        statementNumber = bank?.statementNumber.orEmpty(),
        // Dates arrive as `2026-03-31T00:00`; the form (and PosDateField) hold the date half only.
        statementDate = bank?.statementDate.orEmpty().substringBefore('T'),
        periodStart = bank?.periodStart.orEmpty().substringBefore('T'),
        periodEnd = bank?.periodEnd.orEmpty().substringBefore('T'),
        openingBalance = editableDecimalText(bank?.openingBalance.orEmpty(), 2),
        closingBalance = editableDecimalText(bank?.closingBalance.orEmpty(), 2),
    )
}
