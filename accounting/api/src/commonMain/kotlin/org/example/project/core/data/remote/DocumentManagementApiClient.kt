/**
 * API client interface for document management operations.
 *
 * Stage C Batch 8: throws-style.
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.ProcessingStatus

interface DocumentManagementApiClient {
    suspend fun uploadDocument(
        fileBytes: ByteArray,
        fileName: String,
        fileType: String,
        description: String? = null,
        ocrText: String? = null,
        // Invoice-specific
        vendorName: String? = null,
        invoiceNumber: String? = null,
        invoiceDate: String? = null,
        dueDate: String? = null,
        netAmount: String? = null,
        grossAmount: String? = null,
        taxAmount: String? = null,
        taxRate: String? = null,
        currency: String? = null,
        iban: String? = null,
        paymentReference: String? = null,
        // DATEV AP fields
        supplierCity: String? = null,
        vatId: String? = null,
        accountNo: String? = null,
        bpAccountNo: String? = null,
        deliveryDate: String? = null,
        bookingText: String? = null,
        taxLineItemsJson: String? = null,
        // Bank statement-specific
        bankName: String? = null,
        bic: String? = null,
        accountNumber: String? = null,
        statementNumber: String? = null,
        statementDate: String? = null,
        periodStart: String? = null,
        periodEnd: String? = null,
        openingBalance: String? = null,
        closingBalance: String? = null,
        // Present (even empty) = store a password-protected PDF unlocked. See the upload route.
        unlockPassword: String? = null,
    ): DocumentDto.DocumentResponseDto

    suspend fun getDocumentById(documentId: Long): DocumentDto.DocumentResponseDto

    suspend fun getDocumentMetadata(documentId: Long): DocumentDto.DocumentMetadataDto

    suspend fun getDocumentFile(documentId: Long): ByteArray

    suspend fun searchDocuments(
        fileType: String? = null,
        uploadedBy: String? = null,
        startDate: String? = null,
        endDate: String? = null,
        vendorName: String? = null,
        invoiceNumber: String? = null,
        minAmount: String? = null,
        maxAmount: String? = null,
        bankName: String? = null,
        statementNumber: String? = null,
        processingStatuses: List<ProcessingStatus>? = null,
        searchTerm: String? = null,
        includeInactive: Boolean = false,
        offset: Int = 0,
        limit: Int = 50,
    ): DocumentDto.DocumentListResponseDto

    suspend fun deleteDocument(documentId: Long)

    suspend fun updateBankStatementMetadata(
        documentId: Long,
        request: DocumentDto.UpdateBankStatementMetadataRequestDto,
    ): DocumentDto.DocumentResponseDto

    suspend fun createDocumentLinks(request: DocumentDto.CreateDocumentLinkRequestDto): List<DocumentDto.DocumentLinkResponseDto>

    suspend fun getLinkedDocuments(documentId: Long): List<DocumentDto.DocumentLinkResponseDto>

    suspend fun deleteDocumentLink(linkId: Long)

    suspend fun getUnlinkedDocuments(
        fileType: String? = null,
        search: String? = null,
        excludeTypes: List<String> = emptyList(),
        startDate: String? = null,
        endDate: String? = null,
    ): List<DocumentDto.DocumentResponseDto>

    suspend fun getDocumentTypes(): List<String>

    suspend fun getStatistics(): DocumentDto.DocumentStorageStatisticsDto

    suspend fun exportToDatevAp(
        startDate: String,
        endDate: String,
        periodLabel: String = "",
        excludeKassenbuchLinked: Boolean = true,
    ): DatevExportLogDto

    // ===== Normalized invoice aggregate =====

    /**
     * Reads the normalized invoice for a document.
     *
     * Returns the **earliest** invoice when a document carries several — `source_document_id` is
     * deliberately non-unique. Throws `PosApiException` with `RESOURCE_NOT_FOUND` when the document
     * has no invoice at all, which is the normal state of a freshly scanned PDF.
     */
    suspend fun getDocumentInvoice(documentId: Long): DocumentDto.InvoiceDto

    /** Creates a hand-entered invoice against a document. The server stamps `MANUAL` provenance. */
    suspend fun createDocumentInvoice(
        documentId: Long,
        request: DocumentDto.InvoiceContentRequestDto,
    ): DocumentDto.InvoiceDto

    /**
     * Replaces an invoice's supplier-asserted header.
     *
     * Throws `PosApiException` with `INVOICE_SUPPLIER_CONTENT_READ_ONLY` (409) when the invoice came
     * from a supplier e-invoice. A caller should read `extractionSource` off the aggregate and
     * disable the supplier fields rather than relying on the rejection.
     */
    suspend fun updateInvoiceContent(
        invoiceId: Long,
        request: DocumentDto.InvoiceContentRequestDto,
    ): DocumentDto.InvoiceDto

    /** Updates an invoice's booking fields. `null` leaves a field unchanged; `clearFields` nulls it. */
    suspend fun updateInvoiceBooking(
        invoiceId: Long,
        request: DocumentDto.UpdateInvoiceBookingRequestDto,
    ): DocumentDto.InvoiceDto

    /**
     * Cancels an incoming invoice by a correction (Storno, T9-19) and returns the correction.
     *
     * Throws `PosApiException` with `INVOICE_NOT_CANCELLABLE` (409) when the invoice is already
     * cancelled, is itself a correction, has no document, or is allocated or paid.
     */
    suspend fun cancelInvoice(
        invoiceId: Long,
        request: DocumentDto.CancelInvoiceRequestDto,
    ): DocumentDto.InvoiceDto

    /** Moves a document through the operator workflow. Only `IN_PROGRESS` and `REVIEWED` are accepted. */
    suspend fun updateDocumentStatus(
        documentId: Long,
        request: DocumentDto.UpdateDocumentStatusRequestDto,
    ): DocumentDto.DocumentResponseDto
}
