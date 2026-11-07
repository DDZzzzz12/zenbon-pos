/**
 * Document Repository Interface
 *
 * Defines the contract for document data access operations.
 * Repositories return plain entities; services wrap calls in dbQuery with Result.
 *
 * Repository Layer Responsibility:
 * - Direct database operations (CRUD)
 * - Return domain entities (Document)
 * - No business logic
 * - No DTO conversion (handled by service layer)
 *
 * Type-specific metadata is stored in separate tables:
 * - DocumentInvoiceMetadata for INVOICE documents
 * - DocumentBankStatementMetadata for BANK_STATEMENT documents
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.document.domain.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.document.ClearableBookingField
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.features.document.domain.models.BankStatement
import org.example.project.features.document.domain.models.BankStatementTransaction
import org.example.project.features.document.domain.models.Document
import org.example.project.features.document.domain.models.DocumentBankStatementMetadata
import org.example.project.features.document.domain.models.DocumentInvoiceMetadata
import org.example.project.features.document.domain.models.DocumentLink
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.document.domain.models.InvoiceBooking
import org.example.project.features.document.domain.service.DocumentKassenbuchLinkResolver
import java.math.BigDecimal
import java.util.UUID

/**
 * Repository interface for document data access.
 *
 * Provides CRUD operations and query methods for documents.
 * Returns plain entities; exceptions propagate to the service layer.
 */
interface DocumentRepository {
    /**
     * Creates a new document record in the database.
     *
     * @param filename Original filename
     * @param fileType Document type (INVOICE, BANK_STATEMENT, CONTRACTS, CUSTOM)
     * @param filePath Full path to file on disk
     * @param fileSize File size in bytes
     * @param uploadedAt Upload/generation timestamp
     * @param uploadedBy Employee UUID who uploaded/generated
     * @param contentHash SHA-256 hash of file content (required)
     * @param metadataJson Optional JSON metadata
     * @param ocrText Optional raw OCR text for full-text search
     * @param mediaType MIME type of the file (e.g., "application/pdf", "image/jpeg")
     * @return Created Document entity
     */
    fun create(
        filename: String,
        fileType: String,
        filePath: String,
        fileSize: Long,
        uploadedAt: LocalDateTime,
        uploadedBy: UUID,
        contentHash: String,
        metadataJson: String? = null,
        ocrText: String? = null,
        mediaType: String = "application/pdf",
    ): Document

    /**
     * Retrieves a document by its ID.
     */
    fun findById(id: Long): Document?

    /**
     * Retrieves a document by its file path.
     */
    fun findByFilePath(filePath: String): Document?

    /**
     * Retrieves a document by its content hash (for deduplication).
     */
    fun findByContentHash(contentHash: String): Document?

    /**
     * Searches documents with filtering and pagination.
     *
     * Supports expanded search across filename, OCR text, vendor name, and bank name.
     *
     * @param fileType Optional filter by document type
     * @param fromDate Optional filter by start date
     * @param toDate Optional filter by end date
     * @param month Optional filter by month (YYYY-MM format)
     * @param uploadedBy Optional filter by uploader employee UUID
     * @param activeOnly Only return active documents (default: true)
     * @param searchTerm Search across filename, OCR text, vendor, bank name
     * @param vendorName Filter invoices by vendor name (partial match)
     * @param invoiceNumber Filter invoices by invoice number (exact match)
     * @param minAmount Filter by minimum amount
     * @param maxAmount Filter by maximum amount
     * @param bankName Filter bank statements by bank name (partial match)
     * @param statementNumber Filter bank statements by statement number (exact match)
     * @param processingStatuses Filter by workflow state — any of these statuses. Null **or empty**
     *        means every status; the two are identical on purpose, so a caller cannot ask for
     *        "match nothing".
     * @param offset Pagination offset
     * @param limit Pagination limit
     * @return List of matching Document entities
     */
    fun search(
        fileType: String? = null,
        fromDate: LocalDateTime? = null,
        toDate: LocalDateTime? = null,
        month: String? = null,
        uploadedBy: UUID? = null,
        activeOnly: Boolean = true,
        searchTerm: String? = null,
        vendorName: String? = null,
        invoiceNumber: String? = null,
        minAmount: BigDecimal? = null,
        maxAmount: BigDecimal? = null,
        bankName: String? = null,
        statementNumber: String? = null,
        processingStatuses: List<ProcessingStatus>? = null,
        offset: Int = 0,
        limit: Int = 50,
    ): List<Document>

    /**
     * Counts total documents matching the filter criteria.
     */
    fun count(
        fileType: String? = null,
        fromDate: LocalDateTime? = null,
        toDate: LocalDateTime? = null,
        month: String? = null,
        uploadedBy: UUID? = null,
        activeOnly: Boolean = true,
        searchTerm: String? = null,
        vendorName: String? = null,
        invoiceNumber: String? = null,
        minAmount: BigDecimal? = null,
        maxAmount: BigDecimal? = null,
        bankName: String? = null,
        statementNumber: String? = null,
        processingStatuses: List<ProcessingStatus>? = null,
    ): Long

    /**
     * Soft deletes a document by setting isActive to false.
     * Throws if not found.
     */
    fun softDelete(id: Long)

    /**
     * Reactivates a soft-deleted document.
     * Throws if not found.
     */
    fun reactivate(id: Long)

    /**
     * Updates the metadata JSON for a document.
     * Returns null if not found.
     */
    fun updateMetadata(
        id: Long,
        metadataJson: String,
    ): Document?

    /**
     * Records the e-invoice parse outcome for a document. Returns null if not found.
     *
     * Null rather than throwing, because the sole caller runs after the upload's own transaction
     * has committed and must never fail the upload — see `DocumentManagementServiceImpl`'s
     * `parseEInvoice`.
     *
     * Stores [ProcessingStatus.name]. `DocumentMapper` reads the column back with a bare
     * `valueOf` and no fallback, so anything else makes every later read of the document throw.
     */
    fun updateProcessingStatus(
        id: Long,
        status: ProcessingStatus,
    ): Document?

    /**
     * Retrieves storage statistics for documents.
     */
    fun getStorageStatistics(): Map<String, Long>

    // ===== DOCUMENT LINKING OPERATIONS =====

    /**
     * Creates a link between a document and a bank statement document.
     *
     * @param documentId Document ID (required)
     * @param bankStatementDocumentId Bank statement document ID (required)
     * @param linkedBy Employee UUID who created the link
     * @param linkedAt Timestamp of link creation
     * @param notes Optional notes
     * @return Created DocumentLink entity
     */
    fun createLink(
        documentId: Long,
        bankStatementDocumentId: Long,
        linkedBy: UUID,
        linkedAt: LocalDateTime,
        notes: String? = null,
    ): DocumentLink

    fun getLinkedDocuments(documentId: Long): List<DocumentLink>

    fun deleteLink(linkId: Long)

    fun getUnlinkedDocuments(
        fileType: String? = null,
        activeOnly: Boolean = true,
        offset: Int = 0,
        limit: Int = 50,
        search: String? = null,
        startDate: String? = null,
        endDate: String? = null,
        excludeTypes: List<String> = emptyList(),
    ): List<Document>

    // ===== INVOICE METADATA OPERATIONS =====

    /**
     * Creates invoice metadata for a document.
     */
    fun createInvoiceMetadata(
        documentId: Long,
        vendorName: String? = null,
        invoiceNumber: String? = null,
        invoiceDate: LocalDateTime? = null,
        dueDate: LocalDateTime? = null,
        netAmount: BigDecimal? = null,
        grossAmount: BigDecimal? = null,
        taxAmount: BigDecimal? = null,
        taxRate: BigDecimal? = null,
        currency: String? = null,
        iban: String? = null,
        paymentReference: String? = null,
        supplierCity: String? = null,
        vatId: String? = null,
        accountNo: String? = null,
        bpAccountNo: String? = null,
        deliveryDate: LocalDateTime? = null,
        bookingText: String? = null,
        taxLineItemsJson: String? = null,
        lineItemsJson: String? = null,
        paymentDetailsJson: String? = null,
        discountTermsJson: String? = null,
        invoiceType: String? = null,
        draweeNo: String? = null,
        orderId: String? = null,
        supplierStreet: String? = null,
        supplierZip: String? = null,
        supplierCountry: String? = null,
        supplierPhone: String? = null,
        supplierEmail: String? = null,
        shipToCountry: String? = null,
        shipFromCountry: String? = null,
        paymentConditionsText: String? = null,
        paidAt: LocalDateTime? = null,
        paymentDunningBlock: Boolean? = null,
        costCategoryId: String? = null,
        costCategoryId2: String? = null,
        costAmount: BigDecimal? = null,
        exchangeRate: BigDecimal? = null,
        euTaxRate: BigDecimal? = null,
        buCode: String? = null,
        typeOfReceivable: String? = null,
        deliveryDateStart: LocalDateTime? = null,
        deliveryDateEnd: LocalDateTime? = null,
        additionalInfoJson: String? = null,
    ): DocumentInvoiceMetadata

    /**
     * Updates invoice metadata for a document.
     * Creates if not exists, updates if exists.
     */
    fun updateInvoiceMetadata(
        documentId: Long,
        vendorName: String? = null,
        invoiceNumber: String? = null,
        invoiceDate: LocalDateTime? = null,
        dueDate: LocalDateTime? = null,
        netAmount: BigDecimal? = null,
        grossAmount: BigDecimal? = null,
        taxAmount: BigDecimal? = null,
        taxRate: BigDecimal? = null,
        currency: String? = null,
        iban: String? = null,
        paymentReference: String? = null,
        supplierCity: String? = null,
        vatId: String? = null,
        accountNo: String? = null,
        bpAccountNo: String? = null,
        deliveryDate: LocalDateTime? = null,
        bookingText: String? = null,
        taxLineItemsJson: String? = null,
        lineItemsJson: String? = null,
        paymentDetailsJson: String? = null,
        discountTermsJson: String? = null,
        // Extended DATEV AP fields
        invoiceType: String? = null,
        draweeNo: String? = null,
        orderId: String? = null,
        supplierStreet: String? = null,
        supplierZip: String? = null,
        supplierCountry: String? = null,
        supplierPhone: String? = null,
        supplierEmail: String? = null,
        shipToCountry: String? = null,
        shipFromCountry: String? = null,
        paymentConditionsText: String? = null,
        paidAt: LocalDateTime? = null,
        paymentDunningBlock: Boolean? = null,
        costCategoryId: String? = null,
        costCategoryId2: String? = null,
        costAmount: BigDecimal? = null,
        exchangeRate: BigDecimal? = null,
        euTaxRate: BigDecimal? = null,
        buCode: String? = null,
        typeOfReceivable: String? = null,
        deliveryDateStart: LocalDateTime? = null,
        deliveryDateEnd: LocalDateTime? = null,
        additionalInfoJson: String? = null,
    ): DocumentInvoiceMetadata

    /**
     * Retrieves invoices eligible for DATEV Eingangsrechnungen (AP) export.
     *
     * Reads the normalized [Invoice] model: rows whose `issue_date` falls inside the range, ordered
     * by id so the emitted ledger files are deterministic. An invoice whose source document has been
     * soft-deleted is dropped; one with no source document at all (a bare XRechnung XML) is kept.
     *
     * When [excludeKassenbuchLinked] is true (default), invoices whose source document is linked to
     * a Kassenbuch entry (cash payments, already covered by the Kassenbuch export) are excluded.
     * That link is resolved through [kassenbuchLinks] rather than by querying the Kassenbuch schema
     * from here.
     *
     * Either half of a Storno (a cancelled invoice and the correction that cancels it, T9-19) is
     * left out: the pair nets to zero and the original is no longer an open item.
     *
     * @param startDate Start date (inclusive) in YYYY-MM-DD format
     * @param endDate End date (inclusive) in YYYY-MM-DD format
     * @param excludeKassenbuchLinked Exclude invoices linked to a Kassenbuch entry (default true)
     * @param kassenbuchLinks Resolver used only when [excludeKassenbuchLinked] is true
     * @return In-range invoices, ordered by id
     */
    fun getInvoicesForDatevApExport(
        startDate: String,
        endDate: String,
        excludeKassenbuchLinked: Boolean,
        kassenbuchLinks: DocumentKassenbuchLinkResolver,
    ): List<Invoice>

    /**
     * Retrieves the frozen legacy `document_invoice_metadata` row for a document, if one exists.
     *
     * Nothing writes that table any more. This accessor survives because the upload-path tests use
     * it to assert the **absence** of a legacy row (`EInvoiceUploadIntegrationTest`) — deleting it
     * would delete that guard, not just the method.
     */
    fun getInvoiceMetadata(documentId: Long): DocumentInvoiceMetadata?

    // ===== BANK STATEMENT METADATA OPERATIONS =====

    /**
     * Creates bank statement metadata for a document.
     */
    fun createBankStatementMetadata(
        documentId: Long,
        bankName: String? = null,
        bic: String? = null,
        iban: String? = null,
        accountNumber: String? = null,
        statementNumber: String? = null,
        statementDate: LocalDateTime? = null,
        periodStart: LocalDateTime? = null,
        periodEnd: LocalDateTime? = null,
        openingBalance: BigDecimal? = null,
        closingBalance: BigDecimal? = null,
        currency: String? = null,
        transactionsJson: String? = null,
    ): DocumentBankStatementMetadata

    /**
     * Updates bank statement metadata for a document.
     * Creates if not exists, updates if exists.
     */
    fun updateBankStatementMetadata(
        documentId: Long,
        bankName: String? = null,
        bic: String? = null,
        iban: String? = null,
        accountNumber: String? = null,
        statementNumber: String? = null,
        statementDate: LocalDateTime? = null,
        periodStart: LocalDateTime? = null,
        periodEnd: LocalDateTime? = null,
        openingBalance: BigDecimal? = null,
        closingBalance: BigDecimal? = null,
        currency: String? = null,
        transactionsJson: String? = null,
    ): DocumentBankStatementMetadata

    /**
     * Retrieves bank statement metadata for a document.
     */
    fun getBankStatementMetadata(documentId: Long): DocumentBankStatementMetadata?

    /**
     * Deletes bank statement metadata for a document.
     * Throws if not found.
     */
    fun deleteBankStatementMetadata(documentId: Long)

    // ===== INVOICE AGGREGATE (normalized model) =====

    /** A VAT breakdown row to write. Grouped so the call site cannot silently drop one. */
    data class VatBreakdownRow(
        val vatCategoryCode: String,
        val vatRate: BigDecimal,
        val taxableAmount: BigDecimal,
        val vatAmount: BigDecimal,
        val exemptionReasonCode: String? = null,
        val exemptionReasonText: String? = null,
        /** Per-line DATEV booking text; see the column KDoc in `InvoiceVatBreakdownTable`. */
        val description: String? = null,
    )

    /** An invoice line to write (EN 16931 BG-25). */
    data class InvoiceLineRow(
        val lineId: String,
        val itemName: String,
        val quantity: BigDecimal,
        val unitCode: String,
        val netUnitPrice: BigDecimal,
        val lineNetAmount: BigDecimal,
        val vatCategoryCode: String,
        val vatRate: BigDecimal,
        val itemDescription: String? = null,
        val sellerItemId: String? = null,
        val buyerItemId: String? = null,
        val itemStandardId: String? = null,
    )

    /** EN 16931 BG-20 allowance / BG-21 charge at document level. */
    data class AllowanceChargeRow(
        val isCharge: Boolean,
        val amount: BigDecimal,
        val vatCategoryCode: String,
        val vatRate: BigDecimal,
        val baseAmount: BigDecimal? = null,
        val percentage: BigDecimal? = null,
        val reasonCode: String? = null,
        val reasonText: String? = null,
    )

    /**
     * Creates a supplier invoice header. Everything here is supplier-asserted; our own booking
     * decisions go through [upsertInvoiceBooking].
     *
     * [sourceDocumentId] is nullable on purpose — a bare XRechnung XML has no PDF.
     *
     * [invoiceTypeCode] is BT-3 (UNTDID 1001) and defaults to `380`, an ordinary commercial
     * invoice; `381` is a Gutschrift and `384` a Korrektur. [precedingInvoiceReference] is BT-25,
     * the invoice a Gutschrift or Korrektur refers to, and is null on an ordinary invoice.
     */
    fun createInvoice(
        sourceDocumentId: Long? = null,
        eInvoiceFormat: String? = null,
        extractionSource: String = "MANUAL",
        invoiceNumber: String? = null,
        issueDate: LocalDateTime? = null,
        dueDate: LocalDateTime? = null,
        deliveryDate: LocalDateTime? = null,
        currency: String = "EUR",
        invoiceTypeCode: String = "380",
        precedingInvoiceReference: String? = null,
        buyerReference: String? = null,
        sellerName: String? = null,
        sellerVatId: String? = null,
        sellerTaxNumber: String? = null,
        sellerStreet: String? = null,
        sellerZip: String? = null,
        sellerCity: String? = null,
        sellerCountry: String? = null,
        sellerPhone: String? = null,
        sellerEmail: String? = null,
        paymentIban: String? = null,
        paymentReference: String? = null,
        paymentTermsText: String? = null,
        lineNetTotal: BigDecimal? = null,
        taxExclusiveTotal: BigDecimal? = null,
        taxTotal: BigDecimal? = null,
        taxInclusiveTotal: BigDecimal? = null,
        amountDue: BigDecimal? = null,
    ): Invoice

    /**
     * Replaces the supplier-asserted header of an existing invoice.
     *
     * **Full replace, not a patch.** Every field listed here is written, `null` included. Both
     * callers hold a complete picture of the header when they call: `ParsedInvoiceWriter` has just
     * parsed the supplier's XML into an `EInvoice`, and `DocumentManagementServiceImpl`'s
     * `updateInvoiceSupplierContent` (`PUT /api/invoices/{id}`) receives a whole
     * `InvoiceContentRequestDto` from the capture form. It is the wrong shape for a *partial* edit,
     * which is why the booking side has an operation of its own — see [upsertInvoiceBooking], where
     * a null field means "leave unchanged".
     *
     * Every parameter is **required, with no default**, unlike [createInvoice]. Under a full
     * replace a default would let an omitted argument null a column silently, with no error and no
     * log — and `eInvoiceFormat`, `buyerReference`, `sellerTaxNumber`, `lineNetTotal`, `amountDue`,
     * `invoiceTypeCode` (BT-3) and `precedingInvoiceReference` (BT-25) are exactly the fields a
     * caller is likeliest to hold no value for and be tempted to leave out. Requiring them turns
     * that into a compile error at every call site instead, and forces a new caller to decide what
     * writing each one means rather than inheriting an answer.
     *
     * [extractionSource] **is** reachable from here, and is required like everything else. It was
     * initially left out as pure provenance, on the reasoning that a re-write does not change where
     * the data came from. That is wrong once two writers can reach one row. `ParsedInvoiceWriter`
     * stamps `EINVOICE_XML` on its update leg because every field it writes came out of the
     * supplier's XML; leaving a `MANUAL` label behind would mark XML-derived content as
     * hand-entered, and `updateInvoiceSupplierContent` refuses an edit on exactly that value, so a
     * stale label would turn that guard off. The same requirement is what makes the other caller
     * carry the row's own `extractionSource` forward explicitly rather than by omission. (Nothing
     * re-parses an already-stored document today — `ParsedInvoiceWriter` runs only on upload, where
     * the document is new and its create leg takes over — so that overwrite becomes reachable when
     * a re-parse entry point lands. The parameter is here so it cannot land wrong.)
     *
     * `sourceDocumentId` is identity rather than content and remains unreachable, as do booking
     * fields, which live on [upsertInvoiceBooking] — a re-parse must never overwrite an operator's
     * bookkeeping decisions.
     *
     * @throws NoSuchElementException if no invoice has that id
     */
    fun updateInvoice(
        invoiceId: Long,
        eInvoiceFormat: String?,
        extractionSource: String,
        invoiceNumber: String?,
        issueDate: LocalDateTime?,
        dueDate: LocalDateTime?,
        deliveryDate: LocalDateTime?,
        currency: String,
        invoiceTypeCode: String,
        precedingInvoiceReference: String?,
        buyerReference: String?,
        sellerName: String?,
        sellerVatId: String?,
        sellerTaxNumber: String?,
        sellerStreet: String?,
        sellerZip: String?,
        sellerCity: String?,
        sellerCountry: String?,
        sellerPhone: String?,
        sellerEmail: String?,
        paymentIban: String?,
        paymentReference: String?,
        paymentTermsText: String?,
        lineNetTotal: BigDecimal?,
        taxExclusiveTotal: BigDecimal?,
        taxTotal: BigDecimal?,
        taxInclusiveTotal: BigDecimal?,
        amountDue: BigDecimal?,
    ): Invoice

    /** Retrieves an invoice by its own id. */
    fun getInvoice(id: Long): Invoice?

    /**
     * Retrieves the invoice extracted from a given source document, if any.
     *
     * Several invoices may reference one source document — a scanned PDF can carry more than one —
     * so this returns the earliest by id; a caller needing all of them needs a different method.
     */
    fun getInvoiceBySourceDocument(documentId: Long): Invoice?

    /**
     * Batched counterpart of [getInvoiceBySourceDocument], keyed by source-document id.
     *
     * One query for the whole list, not one per row: the document list maps up to 100 rows per page
     * (`DocumentSearchFilterDto.limit`), and a per-row lookup there would be an N+1 on a paged
     * endpoint.
     *
     * Resolution is identical to the single lookup — **earliest invoice by id** — because
     * `source_document_id` is a plain, non-unique index and one scanned PDF may carry several
     * invoices. A document with no invoice is **absent** from the map rather than mapped to null,
     * and an invoice with a null `source_document_id` (a bare XRechnung with no PDF) appears under
     * no key at all.
     *
     * An empty [documentIds] returns an empty map without touching the database.
     */
    fun getInvoicesBySourceDocuments(documentIds: List<Long>): Map<Long, Invoice>

    /**
     * How many bank transactions are allocated to each document's invoice, for many documents in a fixed
     * number of queries (never one per document). Absent from the map means 0.
     *
     * Incoming invoice: `payment_allocation` rows whose `invoice_id` is the document's invoice (the earliest
     * by id, as [getInvoicesBySourceDocuments] resolves it) and whose source is a bank transaction, counted by
     * **distinct** `bank_transaction_id`. Outgoing invoice: the same over `sales_invoice_id` for the sales
     * invoice whose `document_id` is the document. A reversed allocation has no row left (the Stornolauf and
     * *Rückgängig* delete it), so reversals need no filter. An empty [documentIds] touches no table.
     */
    fun getAllocationCountsByDocuments(documentIds: List<Long>): Map<Long, Int>

    // ===== RECHNUNGEN LIST (the union of two populations) =====

    /**
     * `INVOICE`-type, active documents with **no** invoice row — the *Zu erfassen* population.
     *
     * Document-keyed on purpose: a scan with no e-invoice payload writes no invoice at all, so an
     * invoice-keyed query would render this bucket empty while the work sat invisible.
     *
     * Ordered by `uploaded_at DESC, id DESC` — the id tiebreaker keeps paging from skipping and
     * repeating rows when two documents share an upload timestamp.
     */
    fun findUncapturedInvoiceDocuments(
        limit: Int,
        offset: Long,
    ): List<Document>

    /** Total of [findUncapturedInvoiceDocuments], for the same predicate. */
    fun countUncapturedInvoiceDocuments(): Long

    /**
     * Invoices filtered by review state. [reviewed] null means both.
     *
     * Ordered by `issue_date DESC, id DESC` — a stable order, since two invoices commonly share an
     * issue date and an unstable one makes paging skip and repeat rows.
     */
    fun findInvoicesByReviewState(
        reviewed: Boolean?,
        limit: Int,
        offset: Long,
    ): List<Invoice>

    /** Total of [findInvoicesByReviewState], for the same predicate. */
    fun countInvoicesByReviewState(reviewed: Boolean?): Long

    /** Replaces the whole set — child rows are owned by the invoice, never merged. */
    fun replaceVatBreakdown(
        invoiceId: Long,
        rows: List<VatBreakdownRow>,
    )

    /** Replaces the whole set — child rows are owned by the invoice, never merged. */
    fun replaceInvoiceLines(
        invoiceId: Long,
        rows: List<InvoiceLineRow>,
    )

    /**
     * Replaces the whole set — child rows are owned by the invoice, never merged.
     *
     * Bumps the invoice's `updatedAt`, like the other child replacements: an allowance is
     * supplier-asserted content, not a booking decision.
     *
     * @throws NoSuchElementException if no invoice has that id
     */
    fun replaceAllowancesCharges(
        invoiceId: Long,
        rows: List<AllowanceChargeRow>,
    )

    /**
     * Creates the booking row if absent, updates it if present. Never touches `InvoiceTable`.
     *
     * **Null means "leave unchanged".** A null argument is not written: on update the stored value
     * survives, on create the column keeps its default (`null`, or `false` for
     * `paymentDunningBlock`). This makes a partial edit structurally incapable of wiping a field
     * the caller did not mention — the same reason booking lives in its own table.
     *
     * Clearing is therefore explicit: name the field in [clearFields]. The set defaults to empty,
     * so every existing call site keeps its exact previous meaning and null never gains a second
     * interpretation.
     *
     * [clearFields] is applied **after** the value arguments, so a field both given a value and
     * named for clearing ends up cleared. That combination is contradictory and the service layer
     * rejects it before it reaches here; the ordering exists so the outcome is deterministic rather
     * than dependent on argument order, not so callers can rely on it.
     *
     * `paymentDunningBlock` has no [ClearableBookingField] constant: its column is
     * `NOT NULL DEFAULT false`, so passing `false` already clears it.
     */
    fun upsertInvoiceBooking(
        invoiceId: Long,
        accountNo: String? = null,
        bpAccountNo: String? = null,
        buCode: String? = null,
        bookingText: String? = null,
        costCategoryId: String? = null,
        costCategoryId2: String? = null,
        costAmount: BigDecimal? = null,
        typeOfReceivable: String? = null,
        paymentDunningBlock: Boolean? = null,
        paidAt: LocalDateTime? = null,
        clearFields: Set<ClearableBookingField> = emptySet(),
    ): InvoiceBooking

    // ===== BANK STATEMENT AGGREGATE (normalized model) =====

    /**
     * One statement entry to write — one field per `BankStatementTransactionTable` column.
     *
     * Grouped so the call site cannot silently drop one: the model this replaces kept entries in a
     * JSON blob whose DTO carried four fields, and everything else the extractor produced was lost
     * at that boundary.
     *
     * [amount] is always positive; direction is in [creditDebit] (`CREDIT` | `DEBIT`), matching
     * ISO 20022's `CdtDbtInd`. [rawEntry] is an additive escape hatch for unmodelled CAMT fields —
     * no business logic reads it, but it belongs here so nothing is discarded on the way in.
     */
    data class BankTransactionRow(
        val bookingDate: LocalDateTime,
        val valueDate: LocalDateTime,
        val amount: BigDecimal,
        val currency: String = "EUR",
        val creditDebit: String,
        val status: String? = null,
        val bankTxDomain: String? = null,
        val bankTxFamily: String? = null,
        val bankTxSubFamily: String? = null,
        val accountServicerReference: String? = null,
        val endToEndId: String? = null,
        val mandateId: String? = null,
        val creditorId: String? = null,
        val counterpartyName: String? = null,
        val counterpartyIban: String? = null,
        val counterpartyBic: String? = null,
        val remittanceUnstructured: String? = null,
        val remittanceStructuredRef: String? = null,
        val reversalIndicator: Boolean = false,
        val rawEntry: String? = null,
    )

    /**
     * Creates a bank statement header. Entries go in through [replaceTransactions].
     *
     * [sourceDocumentId] is nullable on purpose — a statement can be entered manually or arrive
     * without a stored file. [format] records how it arrived (`camt053` | `mt940` | `pdf` |
     * `manual`).
     */
    fun createBankStatement(
        sourceDocumentId: Long? = null,
        format: String = "manual",
        accountIban: String? = null,
        accountBic: String? = null,
        bankName: String? = null,
        accountNumber: String? = null,
        statementNumber: String? = null,
        legalSequenceNumber: String? = null,
        statementDate: LocalDateTime? = null,
        periodStart: LocalDateTime? = null,
        periodEnd: LocalDateTime? = null,
        openingBalance: BigDecimal? = null,
        closingBalance: BigDecimal? = null,
        currency: String = "EUR",
    ): BankStatement

    /** Retrieves a bank statement by its own id. */
    fun getBankStatement(id: Long): BankStatement?

    /**
     * Retrieves the bank statement extracted from a given source document, if any.
     *
     * Several statements may reference one source document — a single upload can hold more than
     * one — so this returns the earliest by id; a caller needing all of them needs a different
     * method.
     */
    fun getBankStatementBySourceDocument(documentId: Long): BankStatement?

    /**
     * Batched counterpart of [getBankStatementBySourceDocument], keyed by source-document id.
     *
     * One query for the whole list, not one per row: the document list maps up to 100 rows per page
     * (`DocumentSearchFilterDto.limit`), and a per-row lookup there would be an N+1 on a paged
     * endpoint — the same reason [getInvoicesBySourceDocuments] exists.
     *
     * Resolution is identical to the single lookup — **earliest statement by id** — because
     * `source_document_id` is a plain, non-unique index and one upload may hold several statements.
     * A document with no statement is **absent** from the map rather than mapped to null, and a
     * statement with a null `source_document_id` (entered manually, no file) appears under no key.
     *
     * An empty [documentIds] returns an empty map without touching the database.
     */
    fun getBankStatementsBySourceDocuments(documentIds: List<Long>): Map<Long, BankStatement>

    /** Replaces the whole set — entries are owned by the statement, never merged. */
    fun replaceTransactions(
        statementId: Long,
        rows: List<BankTransactionRow>,
    )

    /** All entries of a statement, oldest id first. */
    fun getTransactions(statementId: Long): List<BankStatementTransaction>

    /**
     * Every other statement recorded for the same account, earliest id first, excluding
     * [excludeStatementId] (the statement being written).
     *
     * Matched on IBAN when there is one and on the legacy account number otherwise, because MT940
     * frequently carries no IBAN — matching on IBAN alone would silently never flag an MT940
     * overlap. With no account identity at all there is nothing to compare against, so nothing is
     * returned.
     *
     * The period comparison is deliberately *not* done here: a real CAMT.053 file may state no
     * period, so the decision needs a fallback the SQL cannot express cleanly. It lives in
     * `StatementDuplicateMarker`.
     */
    fun findStatementsForAccount(
        accountIban: String?,
        accountNumber: String?,
        excludeStatementId: Long,
    ): List<BankStatement>

    /**
     * The earliest and latest booking date of each statement's entries, keyed by statement id.
     *
     * One `GROUP BY` rather than loading every transaction: an account accumulates years of monthly
     * statements, and the overlap decision needs only each one's span. A statement with no entries
     * is **absent** from the map. An empty [statementIds] returns an empty map without touching the
     * database.
     */
    fun getTransactionDateRanges(statementIds: List<Long>): Map<Long, Pair<LocalDateTime, LocalDateTime>>

    /** Every transaction belonging to any of [statementIds]. */
    fun getTransactionsForStatements(statementIds: List<Long>): List<BankStatementTransaction>

    /** Sets the duplicate pointer on [transactionId], or clears it when [duplicateOfId] is null. */
    fun markTransactionDuplicate(
        transactionId: Long,
        duplicateOfId: Long?,
    )

    /** Sets the overlap pointer on [statementId], or clears it when [overlappingId] is null. */
    fun markStatementOverlap(
        statementId: Long,
        overlappingId: Long?,
    )
}
