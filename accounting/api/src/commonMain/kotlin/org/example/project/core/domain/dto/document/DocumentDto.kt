/**
 * Container object for all document-related DTOs.
 *
 * Organizes DTOs by functionality: requests, responses, and operations
 * to maintain clear separation of concerns and consistent API structure.
 *
 * Type-specific metadata is represented via nested DTOs:
 * - InvoiceMetadataDto for INVOICE documents
 * - BankStatementMetadataDto for BANK_STATEMENT documents
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.dto.document

import kotlinx.serialization.Serializable
import org.example.project.core.domain.enums.document.ClearableBookingField
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.core.domain.enums.document.StatementImportNotice

/**
 * Container object for document management DTOs.
 *
 * Provides type-safe data transfer objects for document generation,
 * upload, retrieval, and management operations.
 */
object DocumentDto {
    // ===== TAX LINE ITEM DTO =====

    /**
     * Individual tax line item for split-tax invoices.
     *
     * Represents a single VAT-rate portion of an invoice, e.g., food at 7%
     * or drinks at 19%. When present, the invoice's overall grossAmount is
     * derived as the sum of all line item grossAmounts.
     *
     * @property description Line item description (e.g., "Speisen", "Getränke")
     * @property netAmount Net amount before tax for this line
     * @property taxRate VAT rate percentage (e.g., 7.00, 19.00)
     * @property grossAmount Gross amount including tax for this line
     */
    @Serializable
    data class TaxLineItemDto(
        val description: String = "",
        val netAmount: String,
        val taxRate: String,
        val taxAmount: String = "0",
        val grossAmount: String,
    )

    @Serializable
    data class LineItemDto(
        val position: String? = null,
        val description: String = "",
        val quantity: Double? = null,
        val unit: String? = null,
        val unitPrice: String? = null,
        val netAmount: String? = null,
        val taxRate: String? = null,
        val grossAmount: String? = null,
        val netProductPrice: String? = null,
        val grossProductPrice: String? = null,
        val productId: String? = null,
        val deliveryNoteId: String? = null,
        val taxExemptionReason: String? = null,
        val accountingInfo: AccountingInfoDto? = null,
    )

    @Serializable
    data class AccountingInfoDto(
        val accountNo: String? = null,
        val costCategoryId: String? = null,
        val costCategoryId2: String? = null,
    )

    /**
     * The frozen legacy transaction shape stored inside `document_bank_statement_metadata`'s
     * `transactions_json` blob.
     *
     * Decode-only: nothing writes it any more (the extractor that did was removed), and it is not
     * exposed on any response — `BankStatementMetadataDto.transactions` carries the normalized
     * [BankStatementTransactionDto] instead. It survives so a pre-existing blob still renders.
     */
    @Serializable
    data class BankTransactionDto(
        val date: String? = null,
        val description: String = "",
        val amount: String? = null,
        val type: String? = null,
    )

    // ===== RESPONSE DTOS =====

    /**
     * Lightweight DTO for document list display.
     *
     * Contains only the fields needed for list display: id, filename, fileType,
     * processing status, link count, and flattened metadata fields.
     * Full metadata is fetched separately via GET /documents/{id}/metadata.
     */
    @Serializable
    data class DocumentListItemDto(
        val id: Long,
        val filename: String,
        val fileType: String,
        val processingStatus: ProcessingStatus,
        val isActive: Boolean,
        val linkCount: Int,
        /**
         * Bank transactions allocated to the document's invoice (incoming: `payment_allocation.invoice_id`;
         * outgoing: the sales invoice this document is the PDF of), distinct by transaction, reversed
         * allocations excluded (they are deleted). 0 for every other type and for no invoice.
         * Unlike [linkCount] this is a payment fact.
         */
        val allocationCount: Int = 0,
        /** A normalized invoice row exists for the document (not the legacy metadata fallback). */
        val hasInvoice: Boolean = false,
        // Flattened invoice metadata (only fields used in list display)
        val amount: String? = null,
        val date: String? = null,
        val reference: String? = null,
        val vendorName: String? = null,
        val iban: String? = null,
        val currency: String? = null,
        // Flattened bank statement metadata
        val statementDate: String? = null,
        val closingBalance: String? = null,
        val bankName: String? = null,
        // Link summaries
        val linkedKassenbuchIds: List<Long> = emptyList(),
        val linkedBankStatementIds: List<Long> = emptyList(),
        val linkedDocumentIds: List<Long> = emptyList(),
        val uploadedBy: String? = null,
        val uploadedAt: String? = null,
    )

    /**
     * Paginated list response for document list.
     */
    @Serializable
    data class DocumentListResponseDto(
        val items: List<DocumentListItemDto>,
        val total: Long,
        val offset: Int,
        val limit: Int,
        val hasMore: Boolean,
    )

    /**
     * Response DTO for document metadata only (no file bytes).
     *
     * Returned by GET /documents/{id}/metadata for the preview panel.
     * Contains full type-specific metadata without the file content.
     */
    @Serializable
    data class DocumentMetadataDto(
        val id: Long,
        val filename: String,
        val fileType: String,
        val processingStatus: ProcessingStatus,
        val isActive: Boolean,
        val linkCount: Int,
        // Type-specific metadata
        val invoiceMetadata: InvoiceMetadataDto? = null,
        val bankStatementMetadata: BankStatementMetadataDto? = null,
        // Link summaries
        val linkedKassenbuchIds: List<Long> = emptyList(),
        val linkedBankStatementIds: List<Long> = emptyList(),
        val linkedDocumentIds: List<Long> = emptyList(),
        val uploadedBy: String? = null,
        val uploadedAt: String? = null,
    )

    /**
     * Response DTO for invoice-specific metadata.
     *
     * Contains vendor information, invoice details, amounts with tax breakdown,
     * and payment references extracted from invoice documents.
     *
     * Legacy-shaped: it predates the normalized `invoice` aggregate and both clients still read it.
     * Since 2026-08-11 it is produced from [InvoiceDto]'s model (`DocumentMapper.Invoice.toMetadataDto`)
     * with the frozen `document_invoice_metadata` row as a fallback.
     *
     * Ten fields were removed in that change — `orderId`, `shipToCountry`, `shipFromCountry`,
     * `exchangeRate`, `euTaxRate`, `deliveryDateStart`, `deliveryDateEnd`, `additionalInfo`,
     * `paymentDetails`, `discountTerms`. They have no counterpart in the normalized model (see
     * `LegacyInvoiceSync`'s "What is carried, and what is not"), so they rendered "—" for every
     * invoice captured after Stage 4, and their only consumer anywhere was composeApp's read-only
     * metadata panel. The columns still exist on `document_invoice_metadata`; only the wire stopped
     * carrying them. Do not re-add one without a normalized source behind it.
     *
     * `orderId`, `shipToCountry` and `shipFromCountry` remain declared on
     * `DatevApExportDto.ApLedgerEntryDto`; the AP export stopped supplying them when it moved onto
     * the normalized model, which is an accepted behaviour change from that migration.
     */
    @Serializable
    data class InvoiceMetadataDto(
        /** Company or person issuing the invoice */
        val vendorName: String? = null,
        /** Invoice number (Rechnungsnummer) */
        val invoiceNumber: String? = null,
        /** Invoice date (ISO 8601 format) */
        val invoiceDate: String? = null,
        /** Payment due date (ISO 8601 format) */
        val dueDate: String? = null,
        /** Net amount before tax */
        val netAmount: String? = null,
        /** Gross amount including tax */
        val grossAmount: String? = null,
        /** Tax amount */
        val taxAmount: String? = null,
        /** Tax rate percentage (e.g. "19.00") */
        val taxRate: String? = null,
        /** Currency code (e.g. "EUR") */
        val currency: String? = null,
        /** Payment IBAN */
        val iban: String? = null,
        /** Payment reference / Verwendungszweck */
        val paymentReference: String? = null,
        // ===== DATEV AP (Eingangsrechnungen) FIELDS =====
        /** Supplier city for DATEV accountsPayableLedger */
        val supplierCity: String? = null,
        /** VAT identification number (e.g. "DE123456789") */
        val vatId: String? = null,
        /** Gegenkonto / G/L account number */
        val accountNo: String? = null,
        /** Kreditorenkonto / business partner account number (70000-999999999) */
        val bpAccountNo: String? = null,
        /** Liefer-/Leistungsdatum (delivery/service date, ISO 8601 format) */
        val deliveryDate: String? = null,
        /** Buchungstext (booking text, max 60 chars) */
        val bookingText: String? = null,
        /** Tax line items for split-tax invoices (null for single-tax invoices) */
        val taxLineItems: List<TaxLineItemDto>? = null,
        /** Invoice line items / positions, parsed from the structured e-invoice or entered manually */
        val lineItems: List<LineItemDto>? = null,
        // ===== EXTENDED DATEV AP FIELDS =====
        /** Invoice type: Rechnung, Gutschrift § 14 UStG, etc. */
        val invoiceType: String? = null,
        /** Original invoice number referred to by a Gutschrift (credit note) */
        val draweeNo: String? = null,
        // Supplier address
        val supplierStreet: String? = null,
        val supplierZip: String? = null,
        val supplierCountry: String? = null,
        val supplierPhone: String? = null,
        val supplierEmail: String? = null,
        // Payment conditions
        val paymentConditionsText: String? = null,
        val paidAt: String? = null,
        val paymentDunningBlock: Boolean? = null,
        // Accounting controls
        val costCategoryId: String? = null,
        val costCategoryId2: String? = null,
        val costAmount: String? = null,
        val buCode: String? = null,
        val typeOfReceivable: String? = null,
    )

    /**
     * Response DTO for bank statement-specific metadata.
     *
     * Contains bank identification, statement period, account balances,
     * and extracted transaction data from bank statement documents.
     */
    @Serializable
    data class BankStatementMetadataDto(
        /** Bank name (e.g. Sparkasse, Commerzbank) */
        val bankName: String? = null,
        /** BIC/SWIFT code */
        val bic: String? = null,
        /** Account IBAN */
        val iban: String? = null,
        /** Legacy account number */
        val accountNumber: String? = null,
        /** Statement number (Auszugsnummer) */
        val statementNumber: String? = null,
        /** Statement date (ISO 8601 format) */
        val statementDate: String? = null,
        /** Period start date (ISO 8601 format) */
        val periodStart: String? = null,
        /** Period end date (ISO 8601 format) */
        val periodEnd: String? = null,
        /** Opening balance */
        val openingBalance: String? = null,
        /** Closing balance */
        val closingBalance: String? = null,
        /** Currency code (e.g. "EUR") */
        val currency: String? = null,
        /**
         * Extracted bank transactions.
         *
         * Typed as the **normalized** [BankStatementTransactionDto] rather than the flat
         * [BankTransactionDto] the frozen legacy `transactions_json` blob holds: a statement parsed
         * from CAMT.053/MT940 carries counterparty, remittance and mandate detail that the four-field
         * legacy shape cannot express, and carrying two transaction representations on one wire would
         * mean two things to render. The legacy blob is widened into this shape by
         * `DocumentBankStatementMetadata.toDto()`, where the fields it never had are simply null.
         */
        val transactions: List<BankStatementTransactionDto>? = null,
    )

    /**
     * Response DTO for document metadata.
     *
     * Contains all metadata about a document including file information,
     * upload tracking, type-specific metadata, and download URL for client access.
     */
    @Serializable
    data class DocumentResponseDto(
        /** Unique document ID */
        val id: Long,
        /** Original filename */
        val filename: String,
        /** Document type (INVOICE, BANK_STATEMENT, CONTRACTS, CUSTOM) */
        val fileType: String,
        /** File size in bytes */
        val fileSize: Long,
        /** Upload/generation timestamp (ISO 8601 format) */
        val uploadedAt: String,
        /** Last modification timestamp (ISO 8601 format) - null if never modified */
        val updatedAt: String? = null,
        /** Employee number who uploaded/generated */
        val uploadedBy: String,
        /** Download URL for accessing the document file */
        val downloadUrl: String,
        /** Human-readable file size (e.g., "45.6 KB") */
        val formattedFileSize: String,
        /** Month folder (YYYY-MM format) */
        val monthFolder: String,
        /** MIME type of the document (e.g., "application/pdf", "image/jpeg") */
        val mediaType: String = "application/pdf",
        /**
         * The document's workflow state.
         *
         * Set on upload from the e-invoice parse outcome: `Success` → [ProcessingStatus.COMPLETED]
         * (only once the invoice is actually stored), `NotConformant` →
         * [ProcessingStatus.UNPROCESSABLE], `Failure` → [ProcessingStatus.FAILED], while
         * `NoEInvoiceFound` writes nothing and leaves the [ProcessingStatus.NONE] default — the
         * normal scanned-invoice case. Later values come from human action.
         *
         * `POST /upload` re-reads the document once the parsers have run, so its response already
         * carries the parse outcome.
         */
        val processingStatus: ProcessingStatus = ProcessingStatus.NONE,
        /**
         * **Upload response only** — what the bank-statement import noticed. Empty on every other
         * read, and empty for a file that is not a statement.
         *
         * Not persisted: [StatementImportNotice.BALANCES_DO_NOT_RECONCILE] describes a statement
         * that was refused and therefore has no row to hang a flag on (the document itself stays
         * [ProcessingStatus.FAILED]), and an overlap is also readable afterwards from
         * `BankAccountDto.StatementDto.overlappingStatementId`.
         */
        val statementImportNotices: List<StatementImportNotice> = emptyList(),
        // ===== TYPE-SPECIFIC METADATA =====
        /** Invoice metadata - populated only for INVOICE type documents */
        val invoiceMetadata: InvoiceMetadataDto? = null,
        /** Bank statement metadata - populated only for BANK_STATEMENT type documents */
        val bankStatementMetadata: BankStatementMetadataDto? = null,
        /**
         * Id of the normalized invoice read from or captured against this document, whatever its
         * file type; null when there is none. While it is set the document cannot be deleted — the
         * server refuses with `DELETE_HAS_REFERENCES` and the client disables the action (T9-19).
         */
        val invoiceId: Long? = null,
        /**
         * Set once the invoice named by [invoiceId] has been cancelled by a correction (Storno,
         * T9-19): the Beleg and its invoice stay archived, the correction neutralises the invoice.
         * Null while the invoice is live, and on a document with no invoice.
         */
        val invoiceStorno: InvoiceStornoDto? = null,
        // ===== LINKED ENTITY SUMMARIES =====
        /** Total number of Invoice / BankStatement links */
        val linkCount: Int = 0,
        /**
         * Bank transactions allocated to the document's invoice — see [DocumentListItemDto.allocationCount].
         * Filled by `GET /api/documents/{id}`; write responses (upload, status) leave it 0.
         */
        val allocationCount: Int = 0,
        /** Summary of linked bank statement documents */
        val linkedBankStatements: List<LinkedBankStatementSummary> = emptyList(),
        /** Summary of linked documents */
        val linkedDocuments: List<LinkedDocumentSummary> = emptyList(),
        /** Kassenbuch entry summary if this document is linked to a kassenbuch entry */
        val linkedKassenbuch: LinkedKassenbuchSummary? = null,
        /** Whether this document is active (false = soft-deleted) */
        val isActive: Boolean = true,
    )

    /**
     * Summary of a linked bank statement document for display on document cards.
     */
    @Serializable
    data class LinkedBankStatementSummary(
        val documentId: Long,
        val filename: String,
    )

    /**
     * Summary of a linked document for display on document cards.
     */
    @Serializable
    data class LinkedDocumentSummary(
        val documentId: Long,
        val filename: String,
    )

    /**
     * Summary of a linked kassenbuch entry for display on document cards.
     */
    @Serializable
    data class LinkedKassenbuchSummary(
        val headerId: Long,
        val belegNr: String,
        val belegtext: String,
        val belegDatum: String,
        val totalAmount: String,
    )

    /**
     * Response DTO for document generation operations.
     */
    @Serializable
    data class DocumentGenerationResponseDto(
        val success: Boolean,
        val documentId: Long? = null,
        val downloadUrl: String? = null,
        val errorMessage: String? = null,
    )

    /**
     * Response DTO for paginated document lists.
     */
    @Serializable
    data class DocumentListResponseWithPaginationDto(
        val items: List<DocumentResponseDto>,
        val total: Long,
        val offset: Int,
        val limit: Int,
        val hasMore: Boolean,
    ) {
        companion object {
            fun create(
                items: List<DocumentResponseDto>,
                total: Long,
                offset: Int,
                limit: Int,
            ): DocumentListResponseWithPaginationDto {
                val hasMore = (offset + items.size) < total
                return DocumentListResponseWithPaginationDto(items, total, offset, limit, hasMore)
            }
        }
    }

    /**
     * Response DTO for available document types.
     */
    @Serializable
    data class DocumentTypesResponseDto(
        val types: List<String>,
    )

    // ===== REQUEST DTOS =====

    /**
     * Request DTO for uploading a document.
     *
     * Contains metadata that accompanies the multipart file upload.
     * Supports type-specific metadata for invoices and bank statements.
     */
    @Serializable
    data class UploadDocumentRequestDto(
        /** Original filename */
        val filename: String,
        /** Document type (INVOICE, BANK_STATEMENT, CONTRACTS, CUSTOM) */
        val fileType: String = "CUSTOM",
        /** Optional document description */
        val description: String? = null,
        /** Optional JSON metadata for future AI keyword extraction */
        val metadata: String? = null,
        /** Raw OCR text held for full-text search; supplied by the client-side document scanner on upload */
        val ocrText: String? = null,
        // ===== INVOICE-SPECIFIC FIELDS — IGNORED SINCE STAGE 4, DEPRECATED IN PLACE =====
        //
        // The server no longer stores any of these. `uploadDocument` writes the document row and
        // then parses any embedded e-invoice; invoice data that is not in a structured payload is
        // entered afterwards through adminApp's capture form (POST /api/documents/{id}/invoice).
        //
        // They stay on the wire so an un-upgraded client keeps uploading successfully rather than
        // getting a 400 on the fiscal-document path — losing the metadata is recoverable, losing the
        // ability to file the receipt is not. The server logs a warning when it sees them. Removing
        // them is a later cleanup, once no client sends them.
        //
        // `vendorName` and `invoiceNumber` are the exceptions: they are still read by
        // `FileStorageHelper.generateFilename` and therefore still shape the stored filename.
        // `currency` and `iban` are also still read, by the bank-statement arm, which is unchanged.
        /** Vendor/company name (for INVOICE) */
        val vendorName: String? = null,
        /** Invoice number (for INVOICE) */
        val invoiceNumber: String? = null,
        /** Invoice date (ISO 8601 format, for INVOICE) */
        val invoiceDate: String? = null,
        /** Due date (ISO 8601 format, for INVOICE) */
        val dueDate: String? = null,
        /** Net amount before tax (for INVOICE) — exact decimal string */
        val netAmount: String? = null,
        /** Gross amount including tax (for INVOICE) — exact decimal string */
        val grossAmount: String? = null,
        /** Tax amount (for INVOICE) — exact decimal string */
        val taxAmount: String? = null,
        /** Tax rate percentage (for INVOICE) — exact decimal string */
        val taxRate: String? = null,
        /** Currency code (for INVOICE and BANK_STATEMENT) */
        val currency: String? = null,
        /** IBAN (for INVOICE and BANK_STATEMENT) */
        val iban: String? = null,
        /** Payment reference / Verwendungszweck (for INVOICE) */
        val paymentReference: String? = null,
        // ===== DATEV AP (Eingangsrechnungen) FIELDS =====
        /** Supplier city for DATEV accountsPayableLedger */
        val supplierCity: String? = null,
        /** VAT identification number (e.g. "DE123456789") */
        val vatId: String? = null,
        /** Gegenkonto / G/L account number */
        val accountNo: String? = null,
        /** Kreditorenkonto / business partner account number (70000-999999999) */
        val bpAccountNo: String? = null,
        /** Liefer-/Leistungsdatum (delivery/service date, ISO 8601 format) */
        val deliveryDate: String? = null,
        /** Buchungstext (booking text, max 60 chars) */
        val bookingText: String? = null,
        /** Tax line items for split-tax invoices (for INVOICE) */
        val taxLineItems: List<TaxLineItemDto>? = null,
        // ===== BANK STATEMENT-SPECIFIC FIELDS =====
        /** Bank name (for BANK_STATEMENT) */
        val bankName: String? = null,
        /** BIC/SWIFT code (for BANK_STATEMENT) */
        val bic: String? = null,
        /** Legacy account number (for BANK_STATEMENT) */
        val accountNumber: String? = null,
        /** Statement number (for BANK_STATEMENT) */
        val statementNumber: String? = null,
        /** Statement date (ISO 8601 format, for BANK_STATEMENT) */
        val statementDate: String? = null,
        /** Period start date (ISO 8601 format, for BANK_STATEMENT) */
        val periodStart: String? = null,
        /** Period end date (ISO 8601 format, for BANK_STATEMENT) */
        val periodEnd: String? = null,
        /** Opening balance (for BANK_STATEMENT) — exact decimal string */
        val openingBalance: String? = null,
        /** Closing balance (for BANK_STATEMENT) — exact decimal string */
        val closingBalance: String? = null,
    ) {
        /**
         * Validates the upload request.
         */
        fun validate() {
            require(filename.isNotBlank()) { "Filename is required" }
            require(!filename.contains("..")) { "Filename cannot contain '..' for security" }
            require(!filename.contains("/")) { "Filename cannot contain '/' for security" }
            require(!filename.contains("\\")) { "Filename cannot contain '\\' for security" }
            require(fileType.isNotBlank()) { "File type is required" }
            require(fileType.length <= 50) { "File type must not exceed 50 characters" }
            require(!fileType.contains("/") && !fileType.contains("\\") && !fileType.contains("..")) {
                "File type must not contain path separators"
            }
            // DATEV AP field length validation
            if (supplierCity != null) {
                require(supplierCity.length <= 100) { "Supplier city must not exceed 100 characters" }
            }
            if (vatId != null) {
                require(vatId.length <= 15) { "VAT ID must not exceed 15 characters" }
            }
            if (accountNo != null) {
                require(accountNo.length <= 20) { "Account number must not exceed 20 characters" }
            }
            if (bpAccountNo != null) {
                require(bpAccountNo.length <= 20) { "BP account number must not exceed 20 characters" }
            }
            if (bookingText != null) {
                require(bookingText.length <= 60) { "Booking text must not exceed 60 characters" }
            }
        }
    }

    // ===== FILTER/SEARCH DTOS =====

    /**
     * Query parameters for searching/filtering documents.
     *
     * Supports filtering by type, date range, metadata fields, search term, and pagination.
     */
    @Serializable
    data class DocumentSearchFilterDto(
        /** Filter by document type - null for all */
        val fileType: String? = null,
        /** Filter by start date (ISO 8601 format) - null for no start limit */
        val fromDate: String? = null,
        /** Filter by end date (ISO 8601 format) - null for no end limit */
        val toDate: String? = null,
        /** Filter by month (YYYY-MM format) - null for all months */
        val month: String? = null,
        /** Filter by uploader employee UUID - null for all */
        val uploadedBy: String? = null,
        /** Only return active documents (default: true) */
        val activeOnly: Boolean = true,
        /**
         * Filter by the document's workflow state — any of these statuses.
         *
         * A **set**, because adminApp's work queue buckets several statuses together (Zu erfassen =
         * NONE/UNPROCESSABLE/FAILED, Zu prüfen = COMPLETED, Fertig = REVIEWED) and a bucket has to
         * be one query: `offset`/`limit` are per-request, so one request per status is coherent on
         * page 1 only.
         *
         * Null and empty are identical — both mean "every status". Typed rather than `String`s: an
         * unparseable value cannot reach this DTO at all, which is why [validate] says nothing about
         * it. The route rejects a bad query token with 400.
         *
         * The HTTP query parameter stays singular (`?processingStatus=NONE,FAILED`, comma-separated)
         * — it is the shipped wire name and is pinned by DocumentRoutesTest.
         */
        val processingStatuses: List<ProcessingStatus>? = null,
        // ===== METADATA SEARCH FILTERS =====
        /** Filter invoices by vendor name (partial match) */
        val vendorName: String? = null,
        /** Filter invoices by invoice number (exact match) */
        val invoiceNumber: String? = null,
        /** Filter by minimum amount (invoice gross / bank closing balance) — exact decimal string */
        val minAmount: String? = null,
        /** Filter by maximum amount (invoice gross / bank closing balance) — exact decimal string */
        val maxAmount: String? = null,
        /** Filter bank statements by bank name (partial match) */
        val bankName: String? = null,
        /** Filter bank statements by statement number (exact match) */
        val statementNumber: String? = null,
        /** Search term for filename, OCR text, vendor name, or bank name (case-insensitive) */
        val searchTerm: String? = null,
        /** Page offset (default: 0) */
        val offset: Int = 0,
        /** Page size (default: 50, max: 100) */
        val limit: Int = 50,
    ) {
        /**
         * Validates the search filter parameters.
         */
        fun validate() {
            require(offset >= 0) { "Offset must be non-negative" }
            require(limit in 1..100) { "Limit must be between 1 and 100" }

            if (month != null) {
                require(month.matches(Regex("\\d{4}-\\d{2}"))) {
                    "Month must be in YYYY-MM format"
                }
            }

            if (fileType != null) {
                require(fileType.isNotBlank()) { "File type must not be blank" }
                require(fileType.length <= 50) { "File type must not exceed 50 characters" }
            }
        }
    }

    // ===== STATISTICS DTOS =====

    /**
     * Response DTO for document storage statistics.
     */
    @Serializable
    data class DocumentStorageStatisticsDto(
        /** Total number of active documents */
        val totalDocuments: Long,
        /** Number of formal invoice documents */
        val invoiceCount: Long,
        /** Number of bank statement documents */
        val bankStatementCount: Long,
        /** Number of contract documents */
        val contractsCount: Long,
        /** Number of custom uploaded documents */
        val customCount: Long,
        /** Total storage size in bytes */
        val totalStorageBytes: Long,
        /** Human-readable total storage size */
        val formattedTotalStorage: String,
    )

    // ===== METADATA UPDATE DTOS =====

    /**
     * Request DTO for updating bank statement metadata of a document.
     */
    @Serializable
    data class UpdateBankStatementMetadataRequestDto(
        val bankName: String? = null,
        val bic: String? = null,
        val iban: String? = null,
        val accountNumber: String? = null,
        val statementNumber: String? = null,
        val statementDate: String? = null,
        val periodStart: String? = null,
        val periodEnd: String? = null,
        val openingBalance: String? = null,
        val closingBalance: String? = null,
        val currency: String? = null,
    ) {
        fun validate() {
            if (iban != null) {
                require(iban.length <= 34) { "IBAN must not exceed 34 characters" }
            }
            if (bic != null) {
                require(bic.length <= 11) { "BIC must not exceed 11 characters" }
            }
            if (accountNumber != null) {
                require(accountNumber.length <= 50) { "Account number must not exceed 50 characters" }
            }
            if (statementNumber != null) {
                require(statementNumber.length <= 50) { "Statement number must not exceed 50 characters" }
            }
        }
    }

    // ===== DOCUMENT LINKING DTOS =====

    /**
     * Request DTO for creating document links between documents.
     *
     * Links one or more documents to a bank statement for payment reconciliation.
     * Kassenbuch / Document links are handled via direct FK on KassenbuchHeader.
     */
    @Serializable
    data class CreateDocumentLinkRequestDto(
        val documentIds: List<Long>,
        val bankStatementDocumentId: Long? = null,
        val notes: String? = null,
    ) {
        fun validate() {
            require(documentIds.isNotEmpty()) {
                "At least one document ID must be specified"
            }
            require(documentIds.all { it > 0 }) {
                "All document IDs must be positive"
            }
            require(bankStatementDocumentId != null && bankStatementDocumentId > 0) {
                "Bank statement document ID is required and must be positive"
            }
        }
    }

    /**
     * Response DTO for a document link between two documents.
     */
    @Serializable
    data class DocumentLinkResponseDto(
        val id: Long,
        val documentId: Long,
        val documentFilename: String,
        val documentType: String,
        val bankStatementDocumentId: Long,
        val bankStatementFilename: String,
        val linkedBy: String,
        val linkedAt: String,
        val notes: String? = null,
    )

    /**
     * Response DTO for linked documents query.
     */
    @Serializable
    data class LinkedDocumentsResponseDto(
        val document: DocumentResponseDto,
        val linkedDocuments: List<DocumentResponseDto>,
        val linkCount: Int,
    )

    // ===== NORMALIZED INVOICE / BANK STATEMENT DTOS =====

    /**
     * Normalized invoice aggregate DTO (EN 16931-aligned).
     *
     * All money fields are exact decimal strings, never numbers — see the project money rule.
     * Date fields ([issueDate], [dueDate], [deliveryDate]) are ISO-8601 local date-time strings
     * (`kotlinx.datetime.LocalDateTime.toString()`), matching this file's existing date
     * convention. Seconds are elided when zero: `"2024-01-15T00:00"`, `"2024-01-15T14:30:15"`.
     */
    @Serializable
    data class InvoiceDto(
        val id: Long,
        val sourceDocumentId: Long? = null,
        val eInvoiceFormat: String? = null,
        val extractionSource: String,
        val invoiceNumber: String? = null,
        val issueDate: String? = null,
        val dueDate: String? = null,
        val deliveryDate: String? = null,
        val currency: String,
        /** BT-3 Invoice type code, UNTDID 1001 — `380` Rechnung, `381` Gutschrift, `384` Korrektur. */
        val invoiceTypeCode: String = "380",
        /** BT-25 Preceding invoice reference — the invoice a Gutschrift or Korrektur refers to. */
        val precedingInvoiceReference: String? = null,
        val buyerReference: String? = null,
        val sellerName: String? = null,
        val sellerVatId: String? = null,
        val sellerTaxNumber: String? = null,
        val sellerStreet: String? = null,
        val sellerZip: String? = null,
        val sellerCity: String? = null,
        val sellerCountry: String? = null,
        val sellerPhone: String? = null,
        val sellerEmail: String? = null,
        val paymentIban: String? = null,
        val paymentReference: String? = null,
        val paymentTermsText: String? = null,
        /** Exact decimal string. */
        val lineNetTotal: String? = null,
        /** Exact decimal string. */
        val taxExclusiveTotal: String? = null,
        /** Exact decimal string. */
        val taxTotal: String? = null,
        /** Exact decimal string. */
        val taxInclusiveTotal: String? = null,
        /** Exact decimal string. */
        val amountDue: String? = null,
        val lines: List<InvoiceLineDto> = emptyList(),
        val vatBreakdown: List<VatBreakdownDto> = emptyList(),
        val allowancesCharges: List<AllowanceChargeDto> = emptyList(),
        val booking: InvoiceBookingDto? = null,
        /**
         * The Storno this invoice takes part in — on the cancelled original **and** on the
         * correction that cancels it, the same record either way; null on a live invoice. Either
         * side is read-only while it is set: the server refuses content and booking writes with
         * `INVOICE_CANCELLED_READ_ONLY`.
         */
        val storno: InvoiceStornoDto? = null,
    )

    /**
     * A cancelled incoming invoice and the correction (Gegenbuchung) that neutralises it (T9-19).
     *
     * Nothing is deleted: the original invoice and its Beleg stay archived and visible, and the
     * correction is a second invoice row with the original's totals negated, type code `381`,
     * [reason] as its booking text and BT-25 pointing at the original's number. Both carry a zero
     * residual, so neither counts as open anywhere. Who cancelled is in the audit trail
     * (`CANCEL_INCOMING_INVOICE`).
     *
     * [cancelledAt] is an ISO-8601 local date-time string, matching this file's date convention.
     */
    @Serializable
    data class InvoiceStornoDto(
        val originalInvoiceId: Long,
        val correctionInvoiceId: Long,
        val reason: String? = null,
        val cancelledAt: String,
    )

    /**
     * Cancels an incoming invoice by a correction (`POST /api/invoices/{id}/cancel`).
     *
     * [reason] is mandatory and becomes the correction's booking text, so it is bounded by the
     * DATEV Buchungstext width ([MAX_REASON_LENGTH]).
     */
    @Serializable
    data class CancelInvoiceRequestDto(
        val reason: String,
    ) {
        fun validate() {
            require(reason.isNotBlank()) { "reason must not be blank" }
            require(reason.trim().length <= MAX_REASON_LENGTH) {
                "reason must be at most $MAX_REASON_LENGTH characters"
            }
        }

        companion object {
            /** The DATEV Buchungstext width the reason is stored in. */
            const val MAX_REASON_LENGTH = 60
        }
    }

    @Serializable
    data class InvoiceLineDto(
        val lineId: String,
        val itemName: String,
        val quantity: String,
        val unitCode: String,
        val netUnitPrice: String,
        val lineNetAmount: String,
        val vatCategoryCode: String,
        val vatRate: String,
        val itemDescription: String? = null,
        val sellerItemId: String? = null,
        val buyerItemId: String? = null,
        val itemStandardId: String? = null,
    )

    /**
     * A single VAT-rate breakdown line for an invoice.
     *
     * @property description Not an EN 16931 term — drives the DATEV per-line booking text.
     */
    @Serializable
    data class VatBreakdownDto(
        val vatCategoryCode: String,
        val vatRate: String,
        val taxableAmount: String,
        val vatAmount: String,
        val exemptionReasonCode: String? = null,
        val exemptionReasonText: String? = null,
        val description: String? = null,
    )

    /** EN 16931 BG-20 allowance / BG-21 charge at document level. Amounts are exact decimal strings. */
    @Serializable
    data class AllowanceChargeDto(
        val isCharge: Boolean,
        val amount: String,
        val vatCategoryCode: String,
        val vatRate: String,
        val baseAmount: String? = null,
        val percentage: String? = null,
        val reasonCode: String? = null,
        val reasonText: String? = null,
    )

    /**
     * [paidAt] is an ISO-8601 local date-time string, matching this file's date convention;
     * seconds are elided when zero (`"2024-01-15T00:00"`, `"2024-01-15T14:30:15"`).
     */
    @Serializable
    data class InvoiceBookingDto(
        val accountNo: String? = null,
        val bpAccountNo: String? = null,
        val buCode: String? = null,
        val bookingText: String? = null,
        val costCategoryId: String? = null,
        val costCategoryId2: String? = null,
        val costAmount: String? = null,
        val typeOfReceivable: String? = null,
        val paymentDunningBlock: Boolean = false,
        val paidAt: String? = null,
    )

    /**
     * Request DTO for updating only the booking fields of an invoice.
     *
     * Booking fields only — cannot express a change to supplier-asserted content, so a careless
     * caller cannot re-introduce the destructive-update bug.
     *
     * A `null` field here means "leave unchanged", never "clear" — `upsertInvoiceBooking` treats
     * null as no-op by design so a partial edit can't silently destroy data. Clearing is said out
     * loud instead, by naming the field in [clearFields]; `paidAt` is the case that needs it, since
     * a payment reversal cannot un-mark an invoice as paid through a null.
     *
     * [paidAt] is an ISO-8601 local date-time string, matching this file's date convention;
     * seconds are elided when zero (`"2024-01-15T00:00"`, `"2024-01-15T14:30:15"`).
     */
    @Serializable
    data class UpdateInvoiceBookingRequestDto(
        val accountNo: String? = null,
        val bpAccountNo: String? = null,
        val buCode: String? = null,
        val bookingText: String? = null,
        val costCategoryId: String? = null,
        val costCategoryId2: String? = null,
        val costAmount: String? = null,
        val typeOfReceivable: String? = null,
        val paymentDunningBlock: Boolean? = null,
        val paidAt: String? = null,
        /**
         * Fields to null out. `null` and `[]` both mean "clear nothing".
         *
         * Naming a field here **and** giving it a value is contradictory and is rejected with
         * `VALIDATION_FAILED` / 400 rather than resolved silently.
         *
         * `paymentDunningBlock` has no constant: its column is `NOT NULL DEFAULT false`, so
         * sending `paymentDunningBlock = false` already clears it.
         */
        val clearFields: List<ClearableBookingField>? = null,
    ) {
        /** Rejects the one contradiction the clearing mechanism makes expressible. */
        fun validate() {
            buCode?.let { require(it.length <= 4) { "buCode must be at most 4 characters" } }
            bookingText?.let { require(it.length <= 60) { "bookingText must be at most 60 characters" } }
            costCategoryId?.let { require(it.length <= 36) { "costCategoryId must be at most 36 characters" } }
            accountNo?.let {
                require(it.toLongOrNull() in 1L..999_999_999L) { "accountNo must be numeric, 1-999999999" }
            }
            bpAccountNo?.let {
                require(it.toLongOrNull() in 10_000L..999_999_999L) { "bpAccountNo must be numeric, 10000-999999999" }
            }
            requireDecimal(costAmount, "costAmount")

            val cleared = clearFields.orEmpty().toSet()
            val contradictions =
                buildList {
                    if (ClearableBookingField.ACCOUNT_NO in cleared && accountNo != null) add("accountNo")
                    if (ClearableBookingField.BP_ACCOUNT_NO in cleared && bpAccountNo != null) add("bpAccountNo")
                    if (ClearableBookingField.BU_CODE in cleared && buCode != null) add("buCode")
                    if (ClearableBookingField.BOOKING_TEXT in cleared && bookingText != null) add("bookingText")
                    if (ClearableBookingField.COST_CATEGORY_ID in cleared && costCategoryId != null) {
                        add("costCategoryId")
                    }
                    if (ClearableBookingField.COST_CATEGORY_ID_2 in cleared && costCategoryId2 != null) {
                        add("costCategoryId2")
                    }
                    if (ClearableBookingField.COST_AMOUNT in cleared && costAmount != null) add("costAmount")
                    if (ClearableBookingField.TYPE_OF_RECEIVABLE in cleared && typeOfReceivable != null) {
                        add("typeOfReceivable")
                    }
                    if (ClearableBookingField.PAID_AT in cleared && paidAt != null) add("paidAt")
                }
            require(contradictions.isEmpty()) {
                "cannot both set and clear: ${contradictions.joinToString(", ")}"
            }
        }
    }

    /**
     * The complete supplier-asserted header of an invoice, for creating one by hand or replacing
     * one wholesale.
     *
     * **This is a full write, not a patch.** Every supplier field the normalized model holds is
     * here, and an omitted field is written as `null` — `DocumentRepository.updateInvoice` is a
     * documented full replace, and the capture form loads the whole aggregate and posts the whole
     * header back, so full-replace is the honest shape. A caller that sends a partial body will
     * null the columns it left out.
     *
     * Provenance is **not** here. `extractionSource` and `eInvoiceFormat` describe where the data
     * came from, not what it says: on create the server stamps `MANUAL`, and on update it carries
     * the stored values forward. A request that could set them would let a client relabel supplier
     * content as hand-typed and turn the read-only guard off.
     *
     * `sourceDocumentId` is identity, not content, and is taken from the route path.
     *
     * All money fields are exact decimal strings, never numbers — see the project money rule. Date
     * fields are ISO-8601 local date or date-time strings (`"2026-03-01"` and
     * `"2026-03-01T00:00"` are both accepted).
     *
     * [vatBreakdown] is the one tri-state: `null` leaves the stored breakdown alone, a list
     * **replaces** the whole set (child rows are owned by the invoice, never merged), and `[]`
     * removes it. On create, `null` and `[]` are the same thing.
     */
    @Serializable
    data class InvoiceContentRequestDto(
        val invoiceNumber: String? = null,
        val issueDate: String? = null,
        val dueDate: String? = null,
        val deliveryDate: String? = null,
        val currency: String = "EUR",
        /** BT-3 Invoice type code, UNTDID 1001 — `380` Rechnung, `381` Gutschrift, `384` Korrektur. */
        val invoiceTypeCode: String = "380",
        /** BT-25 Preceding invoice reference — the invoice a Gutschrift or Korrektur refers to. */
        val precedingInvoiceReference: String? = null,
        val buyerReference: String? = null,
        val sellerName: String? = null,
        val sellerVatId: String? = null,
        val sellerTaxNumber: String? = null,
        val sellerStreet: String? = null,
        val sellerZip: String? = null,
        val sellerCity: String? = null,
        val sellerCountry: String? = null,
        val sellerPhone: String? = null,
        val sellerEmail: String? = null,
        val paymentIban: String? = null,
        val paymentReference: String? = null,
        val paymentTermsText: String? = null,
        /** Exact decimal string. */
        val lineNetTotal: String? = null,
        /** Exact decimal string. */
        val taxExclusiveTotal: String? = null,
        /** Exact decimal string. */
        val taxTotal: String? = null,
        /** Exact decimal string. */
        val taxInclusiveTotal: String? = null,
        /** Exact decimal string. */
        val amountDue: String? = null,
        /** `null` leaves the stored breakdown unchanged; a list replaces the whole set. */
        val vatBreakdown: List<VatBreakdownDto>? = null,
    ) {
        /**
         * Server-side half of the capture form's rules (project rule 8: guard at both levels).
         *
         * Only the rules that make a DATEV import fail outright are here — the max-36 invoice
         * number (`<invoiceId>` is silently truncated and character-stripped beyond it), the field
         * lengths DATEV pins, and money that will not parse at all. Amount ≤ 0 is deliberately
         * **not** rejected: a Nullrechnung is a real document, and the export skips it with a
         * warning.
         */
        fun validate() {
            require(currency.length == 3) { "currency must be a 3-letter ISO code" }
            invoiceNumber?.let { require(it.length <= 36) { "invoiceNumber must be at most 36 characters" } }
            sellerName?.let { require(it.length <= 50) { "sellerName must be at most 50 characters" } }
            sellerCity?.let { require(it.length <= 30) { "sellerCity must be at most 30 characters" } }
            sellerVatId?.let { require(it.length <= 15) { "sellerVatId must be at most 15 characters" } }
            paymentReference?.let {
                require(it.length <= 120) { "paymentReference must be at most 120 characters" }
            }

            requireDecimal(lineNetTotal, "lineNetTotal")
            requireDecimal(taxExclusiveTotal, "taxExclusiveTotal")
            requireDecimal(taxTotal, "taxTotal")
            requireDecimal(taxInclusiveTotal, "taxInclusiveTotal")
            requireDecimal(amountDue, "amountDue")
        }
    }

    /**
     * Request DTO for moving a document through the operator workflow.
     *
     * Only [ProcessingStatus.IN_PROGRESS] and [ProcessingStatus.REVIEWED] are accepted — the other
     * values are the parser's to set and the vestigial queue states, and letting a client write
     * them would make the column mean two different things. Anything else is rejected with
     * `VALIDATION_FAILED` / 400.
     */
    @Serializable
    data class UpdateDocumentStatusRequestDto(
        val status: ProcessingStatus,
    )

    /**
     * Date fields ([statementDate], [periodStart], [periodEnd]) are ISO-8601 local date-time
     * strings (`kotlinx.datetime.LocalDateTime.toString()`), matching this file's existing date
     * convention. Seconds are elided when zero: `"2024-01-15T00:00"`, `"2024-01-15T14:30:15"`.
     */
    @Serializable
    data class BankStatementDto(
        val id: Long,
        val sourceDocumentId: Long? = null,
        val format: String,
        val accountIban: String? = null,
        val accountBic: String? = null,
        val bankName: String? = null,
        val accountNumber: String? = null,
        val statementNumber: String? = null,
        val legalSequenceNumber: String? = null,
        val statementDate: String? = null,
        val periodStart: String? = null,
        val periodEnd: String? = null,
        val openingBalance: String? = null,
        val closingBalance: String? = null,
        val currency: String,
        val transactions: List<BankStatementTransactionDto> = emptyList(),
    )

    /**
     * A statement entry.
     *
     * [bookingDate], [valueDate], [amount] and [creditDebit] are nullable **only** to carry the
     * frozen legacy `transactions_json` blob, whose four fields (`date`, `description`, `amount`,
     * `type`) are all optional. A statement parsed into the normalized model always fills them —
     * `BankStatementTransaction` has them non-null in the database — so on that leg they are never
     * null. They were left nullable rather than defaulted because a booking date a hand-entered blob
     * never carried cannot be derived from anything, and inventing one would be indistinguishable
     * from a real value.
     *
     * @property amount Always positive — the direction is carried in [creditDebit].
     * @property bookingDate ISO-8601 local date-time string, seconds elided when zero
     *   (`"2024-01-15T00:00"`, `"2024-01-15T14:30:15"`).
     * @property valueDate ISO-8601 local date-time string, same format as [bookingDate].
     */
    @Serializable
    data class BankStatementTransactionDto(
        val bookingDate: String? = null,
        val valueDate: String? = null,
        val amount: String? = null,
        val currency: String,
        val creditDebit: String? = null,
        val counterpartyName: String? = null,
        val counterpartyIban: String? = null,
        val counterpartyBic: String? = null,
        val endToEndId: String? = null,
        val mandateId: String? = null,
        val creditorId: String? = null,
        val remittanceUnstructured: String? = null,
        val remittanceStructuredRef: String? = null,
        val status: String? = null,
        /**
         * ISO 20022 `BkTxCd` classification (domain/family/sub-family). MT940 has no structured
         * counterpart to CAMT.053's `BkTxCd`, so these stay null for MT940 input.
         */
        val bankTxDomain: String? = null,
        val bankTxFamily: String? = null,
        val bankTxSubFamily: String? = null,
        val accountServicerReference: String? = null,
        val reversalIndicator: Boolean = false,
        /**
         * The id of the earlier-stored transaction this one repeats, or null when it is unique.
         *
         * Written at ingest by `StatementDuplicateMarker` when an overlapping statement is imported
         * a second time. Exposed so a client can say "already imported" instead of leaving the
         * operator to wonder why one payment shows up twice — it is a hint, not a deletion: the row
         * stays, and what the repetition means is the operator's call.
         */
        val duplicateOfTransactionId: Long? = null,
    )
}

/**
 * Converts DocumentResponseDto to lightweight DocumentListItemDto for list display.
 * Used in linking panel where only flattened fields are needed.
 */
fun DocumentDto.DocumentResponseDto.toListItemDto(): DocumentDto.DocumentListItemDto =
    DocumentDto.DocumentListItemDto(
        id = id,
        filename = filename,
        fileType = fileType,
        processingStatus = processingStatus,
        isActive = isActive,
        linkCount = linkCount,
        amount =
            invoiceMetadata?.grossAmount?.toString()
                ?: bankStatementMetadata?.closingBalance?.toString(),
        date =
            invoiceMetadata?.invoiceDate
                ?: bankStatementMetadata?.statementDate,
        reference = invoiceMetadata?.invoiceNumber,
        vendorName = invoiceMetadata?.vendorName,
        iban = invoiceMetadata?.iban,
        currency =
            invoiceMetadata?.currency
                ?: bankStatementMetadata?.currency,
        statementDate = bankStatementMetadata?.statementDate,
        closingBalance = bankStatementMetadata?.closingBalance?.toString(),
        linkedKassenbuchIds = linkedKassenbuch?.let { listOf(it.headerId) } ?: emptyList(),
        linkedBankStatementIds = linkedBankStatements.map { it.documentId },
        linkedDocumentIds = linkedDocuments.map { it.documentId },
        uploadedBy = uploadedBy,
        uploadedAt = uploadedAt,
    )

/**
 * A money string must parse. Deliberately **not** a scale or sign check: the DB column is
 * `DECIMAL(19,4)` and a Nullrechnung is legitimate.
 */
private fun requireDecimal(
    value: String?,
    field: String,
) {
    value?.let {
        require(it.isNotBlank() && it.trim().matches(Regex("""-?\d+(\.\d+)?"""))) {
            "$field must be a decimal string, got '$it'"
        }
    }
}
