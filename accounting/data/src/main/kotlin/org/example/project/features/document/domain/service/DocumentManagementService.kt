/**
 * Document Management Service Interface
 *
 * Defines the contract for document management operations.
 * Handles CRUD operations, file uploads, search, and file serving.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors;
 * the global StatusPages handler converts to application/problem+json.
 * Migrated from Result<T, Error> in Stage B Batch 6.
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.document.domain.service

import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.ProcessingStatus
import java.io.File
import java.util.UUID

/**
 * Service interface for document management operations.
 */
interface DocumentManagementService {
    /**
     * Uploads a document with type-specific metadata.
     *
     * [unlockPassword] **non-null means "unlock requested"** — even an empty string, which is the
     * owner-password-only case. Null stores the file exactly as sent. A requested unlock that fails
     * stores nothing and throws `PDF_PASSWORD_REQUIRED` / `PDF_PASSWORD_INCORRECT`.
     */
    suspend fun uploadDocument(
        file: File,
        request: DocumentDto.UploadDocumentRequestDto,
        uploadedBy: UUID,
        unlockPassword: String? = null,
    ): DocumentDto.DocumentResponseDto

    /**
     * Retrieves document metadata by ID.
     */
    suspend fun getDocumentById(id: Long): DocumentDto.DocumentResponseDto

    /**
     * Retrieves document metadata only (no file bytes) by ID.
     * Used for the preview panel — returns full type-specific metadata.
     */
    suspend fun getDocumentMetadata(id: Long): DocumentDto.DocumentMetadataDto

    /**
     * Retrieves the actual file for download/viewing.
     */
    suspend fun getDocumentFile(id: Long): File

    /**
     * Server-side copy of an existing document: duplicates the file on disk under a new
     * path and creates a new Document row (bypassing the upload content-hash dedup, since a
     * legitimate duplicate is intended). Returns the new document's id.
     *
     * Used by Kassenbuch correction to give the corrected re-entry its own document while
     * leaving the original entry's document untouched (the linked_document_id unique index
     * forbids sharing one Document row across entries).
     *
     * @param sourceDocumentId Document to copy.
     * @param copiedBy Employee performing the copy.
     * @return id of the newly created Document.
     */
    suspend fun copyDocument(
        sourceDocumentId: Long,
        copiedBy: UUID,
    ): Long

    /**
     * Searches documents with filtering and pagination.
     */
    suspend fun searchDocuments(filter: DocumentDto.DocumentSearchFilterDto): DocumentDto.DocumentListResponseDto

    /**
     * Soft deletes a document.
     */
    suspend fun deleteDocument(id: Long)

    /**
     * Permanently deletes a document.
     */
    suspend fun permanentlyDeleteDocument(id: Long)

    /**
     * Reactivates a soft-deleted document.
     */
    suspend fun reactivateDocument(id: Long)

    /**
     * Updates metadata for a document.
     */
    suspend fun updateMetadata(
        id: Long,
        metadata: String,
    ): DocumentDto.DocumentResponseDto

    /**
     * Retrieves storage statistics.
     */
    suspend fun getStorageStatistics(): DocumentDto.DocumentStorageStatisticsDto

    /**
     * Validates a file before upload.
     * Accepts PDF, JPEG, and PNG files.
     */
    suspend fun validateFile(
        file: File,
        maxSizeMb: Int = 10,
    )

    // ===== DOCUMENT LINKING OPERATIONS =====

    suspend fun createDocumentLinks(
        request: DocumentDto.CreateDocumentLinkRequestDto,
        linkedBy: UUID,
    ): List<DocumentDto.DocumentLinkResponseDto>

    suspend fun getLinkedDocuments(documentId: Long): List<DocumentDto.DocumentLinkResponseDto>

    suspend fun deleteDocumentLink(linkId: Long)

    suspend fun getUnlinkedDocuments(
        fileType: String? = null,
        offset: Int = 0,
        limit: Int = 50,
        search: String? = null,
        excludeTypes: List<String> = emptyList(),
        startDate: String? = null,
        endDate: String? = null,
    ): List<DocumentDto.DocumentResponseDto>

    // ===== TYPE-SPECIFIC METADATA OPERATIONS =====

    /**
     * Updates bank statement metadata for a document.
     */
    suspend fun updateBankStatementMetadata(
        id: Long,
        request: DocumentDto.UpdateBankStatementMetadataRequestDto,
    ): DocumentDto.DocumentResponseDto

    // ===== NORMALIZED INVOICE / BANK STATEMENT AGGREGATES =====

    /**
     * Retrieves a normalized invoice with its lines, VAT breakdown and booking, by **invoice** id.
     */
    suspend fun getInvoice(id: Long): DocumentDto.InvoiceDto

    /**
     * Retrieves the invoice extracted from, or entered against, a given source document.
     *
     * **Returns the earliest invoice by id when a document carries several.** `source_document_id`
     * is deliberately non-unique — one scanned PDF may hold more than one invoice — so this cannot
     * be the addressing scheme for a write. A second invoice on the same document is reachable
     * only by its own id, through [updateInvoiceSupplierContent] and [updateInvoiceBooking].
     *
     * @throws org.example.project.infrastructure.errors.PosException `RESOURCE_NOT_FOUND` if the
     *   document does not exist, or exists but has no invoice.
     */
    suspend fun getInvoiceForDocument(documentId: Long): DocumentDto.InvoiceDto

    /**
     * Creates a hand-entered invoice against a document, stamped `extraction_source = MANUAL`.
     *
     * Provenance is the server's to set, never the request's: a client that could stamp
     * `EINVOICE_XML` would turn [updateInvoiceSupplierContent]'s read-only guard off.
     *
     * The document is **not** required to be free of invoices — `source_document_id` is non-unique
     * by design — so a duplicate submission creates a second invoice rather than failing. The
     * calling UI is responsible for not offering the action twice.
     */
    suspend fun createInvoiceForDocument(
        documentId: Long,
        request: DocumentDto.InvoiceContentRequestDto,
    ): DocumentDto.InvoiceDto

    /**
     * Replaces the supplier-asserted header of an invoice, and optionally its VAT breakdown.
     *
     * **Rejected with `INVOICE_SUPPLIER_CONTENT_READ_ONLY` / 409 when the invoice carries
     * `EINVOICE_XML` provenance.** That content came from the supplier's own XML and a typed
     * replacement holds nothing better; Stage 1 already stopped the legacy path overwriting it
     * silently, and this is the explicit-action counterpart, which must say so rather than skip.
     * Booking is unaffected — it lives in `invoice_booking` and goes through
     * [updateInvoiceBooking].
     *
     * **Full replace, not a patch.** An omitted field is written as `null`. See the request DTO.
     */
    suspend fun updateInvoiceSupplierContent(
        invoiceId: Long,
        request: DocumentDto.InvoiceContentRequestDto,
    ): DocumentDto.InvoiceDto

    /**
     * Updates only the booking fields of an invoice, leaving supplier-asserted content untouched.
     *
     * A `null` field in [request] means "leave unchanged", never "clear" — clearing is said out
     * loud through the request's `clearFields`. Naming a field there and giving it a value at the
     * same time is contradictory and is rejected with `VALIDATION_FAILED` / 400.
     */
    suspend fun updateInvoiceBooking(
        invoiceId: Long,
        request: DocumentDto.UpdateInvoiceBookingRequestDto,
    ): DocumentDto.InvoiceDto

    /**
     * Moves a document through the operator workflow.
     *
     * Only [ProcessingStatus.IN_PROGRESS] and [ProcessingStatus.REVIEWED] are accepted; the
     * parser's outcomes and the vestigial queue states are rejected with `VALIDATION_FAILED` / 400.
     */
    suspend fun updateDocumentStatus(
        documentId: Long,
        status: ProcessingStatus,
    ): DocumentDto.DocumentResponseDto

    /**
     * Retrieves a normalized bank statement with its transactions.
     */
    suspend fun getBankStatement(id: Long): DocumentDto.BankStatementDto
}
