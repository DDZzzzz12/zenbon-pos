package org.example.project.features.document.domain.mapper

import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.dto.document.DocumentDto.BankTransactionDto
import org.example.project.core.domain.dto.document.DocumentDto.LineItemDto
import org.example.project.core.domain.dto.document.DocumentDto.TaxLineItemDto
import org.example.project.core.domain.dto.document.DocumentSummaryDto
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.features.document.data.repository.InvoiceStorno
import org.example.project.features.document.domain.models.BankStatement
import org.example.project.features.document.domain.models.BankStatementTransaction
import org.example.project.features.document.domain.models.Document
import org.example.project.features.document.domain.models.DocumentBankStatementMetadata
import org.example.project.features.document.domain.models.DocumentInvoiceMetadata
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.document.domain.models.InvoiceAllowanceCharge
import org.example.project.features.document.domain.models.InvoiceBooking
import org.example.project.features.document.domain.models.InvoiceLine
import org.example.project.features.document.domain.models.InvoiceVatBreakdown
import org.example.project.features.document.domain.service.DocumentKassenbuchLinkResolver
import org.example.project.utils.formatter.toDecimalString
import org.example.project.utils.formatter.toExactDecimalString
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Converts Document entity to DTO.
 *
 * Type-specific metadata is retrieved from separate tables via back-references:
 * - Invoice metadata from the normalized [Invoice] the caller supplies, falling back to the frozen
 *   legacy `document_invoice_metadata` row
 * - Bank statement metadata from the normalized [BankStatement] the caller supplies, falling back to
 *   the legacy `document_bank_statement_metadata` row
 *
 * [linkCounter] supplies the Kassenbuch link; the document feature does not query the Kassenbuch
 * schema itself.
 *
 * ### Where the invoice metadata comes from
 * [invoice] is the document's **normalized** invoice, supplied by the caller — this mapper does not
 * query, so it stays usable inside a caller's ambient transaction without hiding a lookup from it.
 * Passing it explicitly (no default) is deliberate: a default would let a future call site silently
 * fall back to the frozen legacy row, which is the regression this parameter exists to close.
 *
 * When a document has both a normalized invoice and a legacy row — every pre-migration document
 * does, via `InvoiceBackfill` — the **normalized** one wins, whole-record. It is the row
 * `PUT /api/invoices/{id}` and `/booking` edit and the row the DATEV Eingangsrechnungen export books
 * from; a detail panel disagreeing with the export is the dangerous direction. Whole-record rather
 * than per-field, so a displayed panel always corresponds to one real invoice instead of being
 * composed out of two sources.
 *
 * The legacy leg survives only as a fallback: `InvoiceBackfill` is best-effort and logs ERROR with
 * the unmapped ids, so on a shop where it failed this is what keeps the panel populated.
 *
 * ### Where the bank-statement metadata comes from
 * [bankStatement] follows exactly the same rule, for the same reason: an uploaded CAMT.053/MT940 is
 * parsed into the normalized model, so a detail panel still reading `document_bank_statement_metadata`
 * would show nothing for every parsed statement. Whole-record again — the normalized statement or the
 * legacy row, never a blend — and no default, so a new call site cannot silently pick legacy. The
 * legacy leg stays live because adminApp's `BankStatementMetadataForm` is still the only manual-entry
 * path and writes there.
 */
fun toDto(
    document: Document,
    linkCounter: DocumentKassenbuchLinkResolver,
    invoice: Invoice?,
    bankStatement: BankStatement?,
    allocationCount: Int = 0,
): DocumentDto.DocumentResponseDto {
    // fileType is now stored directly as a string (e.g., "INVOICE", "BANK_STATEMENT", or custom type name)
    val resolvedFileType = document.fileType

    // Map type-specific metadata
    val invoiceMetadataDto =
        if (document.isInvoice()) {
            invoice?.toMetadataDto() ?: document.invoiceMetadata?.toDto()
        } else {
            null
        }

    val bankStatementMetadataDto =
        if (document.isBankStatement()) {
            bankStatement?.toMetadataDto() ?: document.bankStatementMetadata?.toDto()
        } else {
            null
        }

    // Build linked entity summaries from Document / BankStatement links
    val allLinks = document.documentLinks.toList() + document.bankStatementLinks.toList()
    var linkCount = allLinks.size

    val linkedBankStatements =
        allLinks
            .filter { it.bankStatementDocument.id.value != document.id.value }
            .map { link ->
                DocumentDto.LinkedBankStatementSummary(
                    documentId = link.bankStatementDocument.id.value,
                    filename = link.bankStatementDocument.filename,
                )
            }.distinctBy { it.documentId }

    val linkedDocuments =
        allLinks
            .filter { it.document.id.value != document.id.value }
            .map { link ->
                DocumentDto.LinkedDocumentSummary(
                    documentId = link.document.id.value,
                    filename = link.document.filename,
                )
            }.distinctBy { it.documentId }

    // Kassenbuch link (1:1 FK) — supplied by the Kassenbuch feature, not queried here
    val linkedKassenbuchSummary = linkCounter.findLinkedKassenbuch(document.id.value)
    if (linkedKassenbuchSummary != null) {
        linkCount += 1
    }

    return DocumentDto.DocumentResponseDto(
        id = document.id.value,
        filename = document.filename,
        fileType = resolvedFileType,
        fileSize = document.fileSize,
        uploadedAt = document.uploadedAt.toString(),
        updatedAt = document.updatedAt?.toString(),
        uploadedBy = document.uploadedBy.employeeNumber,
        downloadUrl = documentFileUrl(document.id.value),
        formattedFileSize = document.formatFileSize(),
        monthFolder = document.getMonthFolder(),
        mediaType = document.mediaType,
        processingStatus = ProcessingStatus.valueOf(document.processingStatus),
        invoiceMetadata = invoiceMetadataDto,
        bankStatementMetadata = bankStatementMetadataDto,
        invoiceId = invoice?.id?.value,
        invoiceStorno = invoice?.stornoDto(),
        linkCount = linkCount,
        allocationCount = allocationCount,
        linkedBankStatements = linkedBankStatements,
        linkedDocuments = linkedDocuments,
        linkedKassenbuch = linkedKassenbuchSummary,
        isActive = document.isActive,
    )
}

/**
 * Converts DocumentInvoiceMetadata entity to DTO.
 */
fun DocumentInvoiceMetadata.toDto(): DocumentDto.InvoiceMetadataDto {
    val json = Json { ignoreUnknownKeys = true }

    val taxLineItems =
        taxLineItemsJson?.let { raw ->
            try {
                json.decodeFromString<List<TaxLineItemDto>>(raw)
            } catch (_: Exception) {
                null
            }
        }
    val lineItems =
        lineItemsJson?.let { raw ->
            try {
                json.decodeFromString<List<LineItemDto>>(raw)
            } catch (_: Exception) {
                null
            }
        }

    return DocumentDto.InvoiceMetadataDto(
        vendorName = vendorName,
        invoiceNumber = invoiceNumber,
        invoiceDate = invoiceDate?.toString(),
        dueDate = dueDate?.toString(),
        netAmount = netAmount?.toDecimalString(),
        grossAmount = grossAmount?.toDecimalString(),
        taxAmount = taxAmount?.toDecimalString(),
        taxRate = taxRate?.toDecimalString(),
        currency = currency,
        iban = iban,
        paymentReference = paymentReference,
        supplierCity = supplierCity,
        vatId = vatId,
        accountNo = accountNo,
        bpAccountNo = bpAccountNo,
        deliveryDate = deliveryDate?.toString(),
        bookingText = bookingText,
        taxLineItems = taxLineItems,
        lineItems = lineItems,
        invoiceType = invoiceType,
        draweeNo = draweeNo,
        supplierStreet = supplierStreet,
        supplierZip = supplierZip,
        supplierCountry = supplierCountry,
        supplierPhone = supplierPhone,
        supplierEmail = supplierEmail,
        paymentConditionsText = paymentConditionsText,
        paidAt = paidAt?.toString(),
        paymentDunningBlock = paymentDunningBlock,
        costCategoryId = costCategoryId,
        costCategoryId2 = costCategoryId2,
        costAmount = costAmount?.toDecimalString(),
        buCode = buCode,
        typeOfReceivable = typeOfReceivable,
    )
}

/**
 * Converts DocumentBankStatementMetadata entity to DTO.
 *
 * The legacy `transactions_json` blob is decoded into its own four-field [BankTransactionDto] shape
 * and then widened to the normalized [DocumentDto.BankStatementTransactionDto] the DTO now carries —
 * see [BankTransactionDto.toBankStatementTransactionDto].
 */
fun DocumentBankStatementMetadata.toDto(): DocumentDto.BankStatementMetadataDto {
    val json = Json { ignoreUnknownKeys = true }

    val transactions =
        transactionsJson?.let { raw ->
            try {
                json.decodeFromString<List<BankTransactionDto>>(raw).map {
                    it.toBankStatementTransactionDto(currency)
                }
            } catch (_: Exception) {
                null
            }
        }

    return DocumentDto.BankStatementMetadataDto(
        bankName = bankName,
        bic = bic,
        iban = iban,
        accountNumber = accountNumber,
        statementNumber = statementNumber,
        statementDate = statementDate?.toString(),
        periodStart = periodStart?.toString(),
        periodEnd = periodEnd?.toString(),
        openingBalance = openingBalance?.toDecimalString(),
        closingBalance = closingBalance?.toDecimalString(),
        currency = currency,
        transactions = transactions,
    )
}

/**
 * Widens a legacy `transactions_json` entry into the normalized transaction DTO.
 *
 * The legacy shape has one date, a free-text description, a possibly signed amount and a free-text
 * `type`; everything the normalized model records beyond that (counterparty, remittance reference,
 * mandate, `BkTxCd` classification) is null, because the blob never held it.
 *
 * Two deliberate derivations, so the widened row still honours the DTO's contract:
 * - [DocumentDto.BankStatementTransactionDto.amount] is documented as always positive with the
 *   direction in `creditDebit`, so a signed legacy amount is split into its magnitude and a
 *   `"DBIT"`/`"CRDT"` direction. An amount that does not parse as a decimal passes through verbatim
 *   rather than being dropped.
 * - `creditDebit` prefers the legacy `type` when it already reads `CRDT`/`DBIT`, and otherwise comes
 *   from the sign. Any other `type` value is free text from an extractor that no longer exists and
 *   is not carried: guessing a direction out of it would be inventing one.
 *
 * [statementCurrency] is the statement's own currency — the legacy blob has no per-line currency and
 * `document_bank_statement_metadata.currency` is NOT NULL.
 */
private fun BankTransactionDto.toBankStatementTransactionDto(statementCurrency: String): DocumentDto.BankStatementTransactionDto {
    val parsedAmount = amount?.toBigDecimalOrNull()

    return DocumentDto.BankStatementTransactionDto(
        bookingDate = date,
        valueDate = date,
        amount = parsedAmount?.abs()?.toExactDecimalString() ?: amount,
        currency = statementCurrency,
        creditDebit = legacyCreditDebit(parsedAmount?.signum()),
        remittanceUnstructured = description.takeIf { it.isNotBlank() },
    )
}

/** `type` when it is already an ISO 20022 direction, else the amount's sign, else unknown. */
private fun BankTransactionDto.legacyCreditDebit(amountSignum: Int?): String? {
    val declared = type?.trim()?.uppercase()
    if (declared == "CRDT" || declared == "DBIT") return declared

    return when {
        amountSignum == null -> null
        amountSignum < 0 -> "DBIT"
        else -> "CRDT"
    }
}

/**
 * The path that serves a document's file: `GET /api/documents/{id}/file` in `DocumentRoutes`.
 *
 * Both DTOs used to hard-code `/api/v1/pdfs/{id}/file`, a path no route serves: following it was
 * refused with a 403 by the authorization plugin, which has no rule for it (E2E-2026-09-20
 * round 2, R2-2). Change this together with the route.
 */
fun documentFileUrl(documentId: Long): String = "/api/documents/$documentId/file"

fun Document.toSummaryDto(): DocumentSummaryDto =
    DocumentSummaryDto(
        id = this.id.value,
        filename = this.filename,
        downloadUrl = documentFileUrl(this.id.value),
    )

/**
 * Converts Document entity to lightweight list item DTO.
 * Used for document list display — only flattened fields needed for list items.
 *
 * ### Where the invoice columns come from
 * [invoice] is the document's **normalized** invoice, supplied by the caller — the list resolves a
 * whole page with one `DocumentRepository.getInvoicesBySourceDocuments` query rather than a lazy
 * per-row referrer, which would be an N+1 on an endpoint that returns up to 100 rows. Passing it
 * explicitly (no default) is deliberate: a default would let a future call site silently fall back
 * to the frozen legacy row, which is the regression this parameter exists to close.
 *
 * When a document has both a normalized invoice and a legacy `document_invoice_metadata` row — every
 * pre-migration document does, via `InvoiceBackfill` — the **normalized** one wins, and wins for all
 * six columns together. It is the row `PUT /api/invoices/{id}` edits and the row the DATEV
 * Eingangsrechnungen export books from; a list disagreeing with the export is the dangerous
 * direction. Whole-record rather than per-field, so a displayed row always corresponds to one real
 * invoice instead of being composed out of two sources.
 *
 * The legacy leg survives only as a fallback: `InvoiceBackfill` is best-effort and logs ERROR with
 * the unmapped ids, so on a shop where it failed this is what keeps the list populated.
 *
 * The **bank-statement** columns follow the same rule, and for the same reason. Uploaded CAMT.053 and
 * MT940 statements are parsed into the normalized model by `ParsedStatementWriter`, so [bankStatement]
 * — batched by `DocumentRepository.getBankStatementsBySourceDocuments`, not a per-row referrer — wins
 * whole-record. `document_bank_statement_metadata` remains the fallback because adminApp's
 * `BankStatementMetadataForm` is still the only manual-entry path and writes there; a hand-entered
 * statement has no normalized row at all.
 */
fun Document.toListItemDto(
    linkCounter: DocumentKassenbuchLinkResolver,
    invoice: Invoice?,
    bankStatement: BankStatement?,
    allocationCount: Int,
): DocumentDto.DocumentListItemDto {
    val legacy = invoiceMetadata
    val bank = bankStatement?.toListColumns() ?: bankStatementMetadata?.toListColumns() ?: BankStatementListColumns()

    // Collect all links (same pattern as toDto)
    val allLinks = documentLinks.toList() + bankStatementLinks.toList()
    val linkedKassenbuch = linkCounter.findLinkedKassenbuch(id.value)

    val linkedBankStatementIds =
        allLinks
            .filter { it.bankStatementDocument.id.value != id.value }
            .map { it.bankStatementDocument.id.value }

    val linkedDocumentIds =
        allLinks
            .filter { it.document.id.value != id.value }
            .map { it.document.id.value }

    val invoiceColumns = invoice?.toListColumns() ?: legacy?.toListColumns() ?: InvoiceListColumns()

    return DocumentDto.DocumentListItemDto(
        id = id.value,
        filename = filename,
        fileType = fileType,
        processingStatus = ProcessingStatus.valueOf(processingStatus),
        isActive = isActive,
        linkCount = allLinks.size + if (linkedKassenbuch != null) 1 else 0,
        allocationCount = allocationCount,
        hasInvoice = invoice != null,
        amount = invoiceColumns.amount,
        date = invoiceColumns.date ?: bank.statementDate,
        reference = invoiceColumns.reference,
        vendorName = invoiceColumns.vendorName,
        iban = invoiceColumns.iban,
        currency = invoiceColumns.currency,
        statementDate = bank.statementDate,
        closingBalance = bank.closingBalance,
        bankName = bank.bankName,
        linkedKassenbuchIds = linkedKassenbuch?.let { listOf(it.headerId) } ?: emptyList(),
        linkedBankStatementIds = linkedBankStatementIds,
        linkedDocumentIds = linkedDocumentIds,
        uploadedBy = uploadedBy?.employeeNumber,
        uploadedAt = uploadedAt?.toString(),
    )
}

/**
 * The six invoice columns a document list row shows, in wire form.
 *
 * A carrier rather than six coalescing expressions, so the choice between the normalized invoice and
 * the frozen legacy row is made **once** for the whole row — see [toListItemDto].
 */
private data class InvoiceListColumns(
    val amount: String? = null,
    val date: String? = null,
    val reference: String? = null,
    val vendorName: String? = null,
    val iban: String? = null,
    val currency: String? = null,
)

/**
 * The three bank-statement columns a document list row shows, in wire form.
 *
 * Same carrier trick as [InvoiceListColumns]: the choice between the normalized statement and the
 * legacy row is made **once** for the whole row — see [toListItemDto].
 */
private data class BankStatementListColumns(
    val statementDate: String? = null,
    val closingBalance: String? = null,
    val bankName: String? = null,
)

/** Normalized statement → list columns. */
private fun BankStatement.toListColumns(): BankStatementListColumns =
    BankStatementListColumns(
        statementDate = statementDate?.toString(),
        closingBalance = closingBalance?.toDecimalString(),
        bankName = bankName,
    )

/** Legacy metadata → list columns; the fallback leg only. */
private fun DocumentBankStatementMetadata.toListColumns(): BankStatementListColumns =
    BankStatementListColumns(
        statementDate = statementDate?.toString(),
        closingBalance = closingBalance?.toDecimalString(),
        bankName = bankName,
    )

/** Normalized invoice → list columns. `taxInclusiveTotal` is the gross the list shows. */
private fun Invoice.toListColumns(): InvoiceListColumns =
    InvoiceListColumns(
        amount = taxInclusiveTotal?.toDecimalString(),
        date = issueDate?.toString(),
        reference = invoiceNumber,
        vendorName = sellerName,
        iban = paymentIban,
        currency = currency,
    )

/** Legacy metadata → list columns; the fallback leg only. Mirrors `LegacyInvoiceSync.writeHeader`. */
private fun DocumentInvoiceMetadata.toListColumns(): InvoiceListColumns =
    InvoiceListColumns(
        amount = grossAmount?.toDecimalString(),
        date = invoiceDate?.toString(),
        reference = invoiceNumber,
        vendorName = vendorName,
        iban = iban,
        currency = currency,
    )

/**
 * Normalized invoice → the legacy-shaped [DocumentDto.InvoiceMetadataDto] the detail endpoints and
 * both clients still consume.
 *
 * The exact inverse of `LegacyInvoiceSync.writeHeader`/`writeBooking`/`vatRows`, which is what made
 * the two models interchangeable in the first place. Money renders through the same rule the rest of
 * this file uses: document-level totals and the booking `costAmount` at scale 2 via [toDecimalString],
 * line-level values at their stored scale via [toExactDecimalString], so a four-decimal BT-146 unit
 * price is not silently rounded.
 *
 * Three deliberate nulls:
 * - `invoiceType` — the normalized counterpart is BT-3 `invoiceTypeCode` (UNTDID 1001). Putting
 *   `"381"` under the panel's "Rechnungsart" would show an operator a code where they expect
 *   "Gutschrift", and mapping the code to German here would hardcode one language in a DTO and
 *   duplicate `adminApp`'s `InvoiceTypeCode`/`invoiceTypeCodeLabelKey`, which already does it in all
 *   three. The code is exposed correctly on `InvoiceDto.invoiceTypeCode`.
 * - a line's `grossAmount`, `netProductPrice`, `grossProductPrice`, `deliveryNoteId`,
 *   `taxExemptionReason`, `accountingInfo` — EN 16931 BG-25 records a net line amount and a rate,
 *   not a gross. Deriving one here would invent a value the supplier never asserted.
 * - `taxRate` when the invoice has more than one VAT breakdown row — the legacy header rate was
 *   nulled for exactly those invoices too, because a single header rate cannot represent a mixed
 *   7 %/19 % bill. `taxLineItems` carries the per-rate split.
 *
 * Requires an ambient transaction — [Invoice.lines], [Invoice.vatBreakdown] and [Invoice.booking]
 * are lazy Exposed referrers.
 */
fun Invoice.toMetadataDto(): DocumentDto.InvoiceMetadataDto {
    val vatRows = vatBreakdown.sortedBy { it.id.value }
    val bookkeeping = booking

    return DocumentDto.InvoiceMetadataDto(
        vendorName = sellerName,
        invoiceNumber = invoiceNumber,
        invoiceDate = issueDate?.toString(),
        dueDate = dueDate?.toString(),
        netAmount = taxExclusiveTotal?.toDecimalString(),
        grossAmount = taxInclusiveTotal?.toDecimalString(),
        taxAmount = taxTotal?.toDecimalString(),
        taxRate = vatRows.singleOrNull()?.vatRate?.toExactDecimalString(),
        currency = currency,
        iban = paymentIban,
        paymentReference = paymentReference,
        supplierCity = sellerCity,
        vatId = sellerVatId,
        accountNo = bookkeeping?.accountNo,
        bpAccountNo = bookkeeping?.bpAccountNo,
        deliveryDate = deliveryDate?.toString(),
        bookingText = bookkeeping?.bookingText,
        taxLineItems = vatRows.map { it.toTaxLineItemDto() },
        lineItems = lines.sortedBy { it.id.value }.map { it.toLegacyLineItemDto() },
        invoiceType = null,
        draweeNo = precedingInvoiceReference,
        supplierStreet = sellerStreet,
        supplierZip = sellerZip,
        supplierCountry = sellerCountry,
        supplierPhone = sellerPhone,
        supplierEmail = sellerEmail,
        paymentConditionsText = paymentTermsText,
        paidAt = bookkeeping?.paidAt?.toString(),
        paymentDunningBlock = bookkeeping?.paymentDunningBlock,
        costCategoryId = bookkeeping?.costCategoryId,
        costCategoryId2 = bookkeeping?.costCategoryId2,
        costAmount = bookkeeping?.costAmount?.toDecimalString(),
        buCode = bookkeeping?.buCode,
        typeOfReceivable = bookkeeping?.typeOfReceivable,
    )
}

/**
 * BG-23 row → the legacy tax line the panel renders.
 *
 * `grossAmount` is `taxable + vat` **by addition**, which is the exact inverse of
 * `LegacyInvoiceSync.splitGross` — it derived the pair from the gross by subtraction so that
 * `taxable + vat == gross` holds bit-for-bit. Recomputing the gross from the rate instead would
 * reintroduce the rounding error that invariant exists to prevent.
 */
private fun InvoiceVatBreakdown.toTaxLineItemDto(): TaxLineItemDto =
    TaxLineItemDto(
        description = description.orEmpty(),
        netAmount = taxableAmount.toExactDecimalString(),
        taxRate = vatRate.toExactDecimalString(),
        taxAmount = vatAmount.toExactDecimalString(),
        grossAmount = (taxableAmount + vatAmount).toExactDecimalString(),
    )

/**
 * BG-25 line → the legacy line item the panel renders.
 *
 * `quantity` is the one numeric that stays a `Double` — it is a count, not money (see the surviving
 * non-money `Double`s in the root CLAUDE.md), and the DTO has declared it that way since before the
 * normalized model. Every price on the line stays an exact decimal string.
 */
private fun InvoiceLine.toLegacyLineItemDto(): LineItemDto =
    LineItemDto(
        position = lineId,
        description = itemName,
        quantity = quantity.toDouble(),
        unit = unitCode,
        unitPrice = netUnitPrice.toExactDecimalString(),
        netAmount = lineNetAmount.toExactDecimalString(),
        taxRate = vatRate.toExactDecimalString(),
        productId = sellerItemId,
    )

/**
 * Converts Document entity to metadata DTO (no file bytes).
 * Used for GET /documents/{id}/metadata — full metadata without file content.
 *
 * [invoice] follows the same rule as [toDto]'s: the normalized invoice wins whole-record, the frozen
 * legacy row is the fallback, and there is no default so a new call site cannot silently pick legacy.
 * [bankStatement] behaves identically for the statement panel.
 */
fun Document.toMetadataDto(
    linkCounter: DocumentKassenbuchLinkResolver,
    invoice: Invoice?,
    bankStatement: BankStatement?,
): DocumentDto.DocumentMetadataDto {
    val legacy = invoiceMetadata
    val bank = bankStatementMetadata

    // Collect all links (same pattern as toDto)
    val allLinks = documentLinks.toList() + bankStatementLinks.toList()
    val linkedKassenbuch = linkCounter.findLinkedKassenbuch(id.value)

    val linkedBankStatementIds =
        allLinks
            .filter { it.bankStatementDocument.id.value != id.value }
            .map { it.bankStatementDocument.id.value }

    val linkedDocumentIds =
        allLinks
            .filter { it.document.id.value != id.value }
            .map { it.document.id.value }

    val invDto = if (isInvoice()) invoice?.toMetadataDto() ?: legacy?.toDto() else null
    val bankDto = if (isBankStatement()) bankStatement?.toMetadataDto() ?: bank?.toDto() else null

    return DocumentDto.DocumentMetadataDto(
        id = id.value,
        filename = filename,
        fileType = fileType,
        processingStatus = ProcessingStatus.valueOf(processingStatus),
        isActive = isActive,
        linkCount = allLinks.size + if (linkedKassenbuch != null) 1 else 0,
        invoiceMetadata = invDto,
        bankStatementMetadata = bankDto,
        linkedKassenbuchIds = linkedKassenbuch?.let { listOf(it.headerId) } ?: emptyList(),
        linkedBankStatementIds = linkedBankStatementIds,
        linkedDocumentIds = linkedDocumentIds,
        uploadedBy = uploadedBy?.employeeNumber,
        uploadedAt = uploadedAt?.toString(),
    )
}

// ===== NORMALIZED INVOICE / BANK STATEMENT MAPPERS =====

/*
 * Money formatting rule for the normalized model:
 * - Document-level totals (invoice totals, statement balances) and the booking `costAmount` are
 *   read as money, so they render at scale 2 via [toDecimalString].
 * - Line-level values (unit price, quantity, line net amount, VAT breakdown amounts, transaction
 *   amount) render with their stored scale via [toExactDecimalString]. Pinning scale 2 here would
 *   round a four-decimal BT-146 unit price and silently defeat the DECIMAL(19,4) columns.
 * Rates (`vatRate`) are not money; they render exactly, preserving the stored DECIMAL(5,2).
 */

/**
 * Converts an [Invoice] aggregate to its DTO, including lines, VAT breakdown and booking.
 *
 * Requires an ambient transaction — the child collections are lazy Exposed referrers.
 */
fun Invoice.toDto(): DocumentDto.InvoiceDto =
    DocumentDto.InvoiceDto(
        id = id.value,
        sourceDocumentId = sourceDocument?.id?.value,
        eInvoiceFormat = eInvoiceFormat,
        extractionSource = extractionSource,
        invoiceNumber = invoiceNumber,
        issueDate = issueDate?.toString(),
        dueDate = dueDate?.toString(),
        deliveryDate = deliveryDate?.toString(),
        currency = currency,
        invoiceTypeCode = invoiceTypeCode,
        precedingInvoiceReference = precedingInvoiceReference,
        buyerReference = buyerReference,
        sellerName = sellerName,
        sellerVatId = sellerVatId,
        sellerTaxNumber = sellerTaxNumber,
        sellerStreet = sellerStreet,
        sellerZip = sellerZip,
        sellerCity = sellerCity,
        sellerCountry = sellerCountry,
        sellerPhone = sellerPhone,
        sellerEmail = sellerEmail,
        paymentIban = paymentIban,
        paymentReference = paymentReference,
        paymentTermsText = paymentTermsText,
        lineNetTotal = lineNetTotal?.toDecimalString(),
        taxExclusiveTotal = taxExclusiveTotal?.toDecimalString(),
        taxTotal = taxTotal?.toDecimalString(),
        taxInclusiveTotal = taxInclusiveTotal?.toDecimalString(),
        amountDue = amountDue?.toDecimalString(),
        lines = lines.sortedBy { it.id.value }.map { it.toDto() },
        vatBreakdown = vatBreakdown.sortedBy { it.id.value }.map { it.toDto() },
        allowancesCharges = allowancesCharges.sortedBy { it.id.value }.map { it.toDto() },
        booking = booking?.toDto(),
        storno = stornoDto(),
    )

/**
 * The Storno this invoice takes part in, from either side (T9-19), or null for a live invoice. The
 * reason is the correction's booking text, the time its creation; who did it is in the audit trail.
 */
fun Invoice.stornoDto(): DocumentDto.InvoiceStornoDto? {
    val (original, correction) = InvoiceStorno.pairOf(this) ?: return null
    return DocumentDto.InvoiceStornoDto(
        originalInvoiceId = original.id.value,
        correctionInvoiceId = correction.id.value,
        reason = correction.booking?.bookingText,
        cancelledAt = correction.createdAt.toString(),
    )
}

/** Converts an [InvoiceLine] (EN 16931 BG-25) to its DTO. */
fun InvoiceLine.toDto(): DocumentDto.InvoiceLineDto =
    DocumentDto.InvoiceLineDto(
        lineId = lineId,
        itemName = itemName,
        quantity = quantity.toExactDecimalString(),
        unitCode = unitCode,
        netUnitPrice = netUnitPrice.toExactDecimalString(),
        lineNetAmount = lineNetAmount.toExactDecimalString(),
        vatCategoryCode = vatCategoryCode,
        vatRate = vatRate.toExactDecimalString(),
        itemDescription = itemDescription,
        sellerItemId = sellerItemId,
        buyerItemId = buyerItemId,
        itemStandardId = itemStandardId,
    )

/** Converts an [InvoiceVatBreakdown] row (EN 16931 BG-23) to its DTO. */
fun InvoiceVatBreakdown.toDto(): DocumentDto.VatBreakdownDto =
    DocumentDto.VatBreakdownDto(
        vatCategoryCode = vatCategoryCode,
        vatRate = vatRate.toExactDecimalString(),
        taxableAmount = taxableAmount.toExactDecimalString(),
        vatAmount = vatAmount.toExactDecimalString(),
        exemptionReasonCode = exemptionReasonCode,
        exemptionReasonText = exemptionReasonText,
        description = description,
    )

/** Converts an [InvoiceAllowanceCharge] row (EN 16931 BG-20/BG-21) to its DTO. */
fun InvoiceAllowanceCharge.toDto(): DocumentDto.AllowanceChargeDto =
    DocumentDto.AllowanceChargeDto(
        isCharge = isCharge,
        amount = amount.toExactDecimalString(),
        vatCategoryCode = vatCategoryCode,
        vatRate = vatRate.toExactDecimalString(),
        baseAmount = baseAmount?.toExactDecimalString(),
        percentage = percentage?.toExactDecimalString(),
        reasonCode = reasonCode,
        reasonText = reasonText,
    )

/** Converts an [InvoiceBooking] row to its DTO. */
fun InvoiceBooking.toDto(): DocumentDto.InvoiceBookingDto =
    DocumentDto.InvoiceBookingDto(
        accountNo = accountNo,
        bpAccountNo = bpAccountNo,
        buCode = buCode,
        bookingText = bookingText,
        costCategoryId = costCategoryId,
        costCategoryId2 = costCategoryId2,
        costAmount = costAmount?.toDecimalString(),
        typeOfReceivable = typeOfReceivable,
        paymentDunningBlock = paymentDunningBlock,
        paidAt = paidAt?.toString(),
    )

/**
 * Converts a [BankStatement] aggregate to its DTO, including its transactions.
 *
 * Requires an ambient transaction — [BankStatement.transactions] is a lazy Exposed referrer.
 */
fun BankStatement.toDto(): DocumentDto.BankStatementDto =
    DocumentDto.BankStatementDto(
        id = id.value,
        sourceDocumentId = sourceDocument?.id?.value,
        format = format,
        accountIban = accountIban,
        accountBic = accountBic,
        bankName = bankName,
        accountNumber = accountNumber,
        statementNumber = statementNumber,
        legalSequenceNumber = legalSequenceNumber,
        statementDate = statementDate?.toString(),
        periodStart = periodStart?.toString(),
        periodEnd = periodEnd?.toString(),
        openingBalance = openingBalance?.toDecimalString(),
        closingBalance = closingBalance?.toDecimalString(),
        currency = currency,
        transactions = transactions.sortedBy { it.id.value }.map { it.toDto() },
    )

/**
 * Normalized statement → the legacy-shaped [DocumentDto.BankStatementMetadataDto] the document detail
 * endpoints and both clients still consume.
 *
 * The inverse of `DocumentBankStatementMetadata.toDto()`, field for field, so repointing the detail
 * panel at the normalized model changes what is rendered but not how. Balances are document-level
 * money and render at scale 2 via [toDecimalString], matching [BankStatement.toDto].
 *
 * Two normalized columns have no legacy counterpart and are therefore not carried: `format`
 * (`camt053`/`mt940`/…) and `legalSequenceNumber`. Both are on [DocumentDto.BankStatementDto], which
 * `GET /api/documents/{id}/bank-statement` serves.
 *
 * Requires an ambient transaction — [BankStatement.transactions] is a lazy Exposed referrer.
 */
fun BankStatement.toMetadataDto(): DocumentDto.BankStatementMetadataDto =
    DocumentDto.BankStatementMetadataDto(
        bankName = bankName,
        bic = accountBic,
        iban = accountIban,
        accountNumber = accountNumber,
        statementNumber = statementNumber,
        statementDate = statementDate?.toString(),
        periodStart = periodStart?.toString(),
        periodEnd = periodEnd?.toString(),
        openingBalance = openingBalance?.toDecimalString(),
        closingBalance = closingBalance?.toDecimalString(),
        currency = currency,
        transactions = transactions.sortedBy { it.id.value }.map { it.toDto() },
    )

/**
 * Converts a [BankStatementTransaction] to its DTO.
 *
 * `rawEntry` is deliberately not carried: it is an escape hatch for unmodelled CAMT fields that no
 * business logic reads, and the DTO has no field for it.
 */
fun BankStatementTransaction.toDto(): DocumentDto.BankStatementTransactionDto =
    DocumentDto.BankStatementTransactionDto(
        bookingDate = bookingDate.toString(),
        valueDate = valueDate.toString(),
        amount = amount.toExactDecimalString(),
        currency = currency,
        creditDebit = creditDebit,
        counterpartyName = counterpartyName,
        counterpartyIban = counterpartyIban,
        counterpartyBic = counterpartyBic,
        endToEndId = endToEndId,
        mandateId = mandateId,
        creditorId = creditorId,
        remittanceUnstructured = remittanceUnstructured,
        remittanceStructuredRef = remittanceStructuredRef,
        status = status,
        bankTxDomain = bankTxDomain,
        bankTxFamily = bankTxFamily,
        bankTxSubFamily = bankTxSubFamily,
        accountServicerReference = accountServicerReference,
        reversalIndicator = reversalIndicator,
        duplicateOfTransactionId = duplicateOfTransaction?.id?.value,
    )
