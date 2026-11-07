package org.example.project.admin.features.documents

import org.example.project.core.data.remote.DocumentManagementApiClient
import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException

/**
 * Fake for the document-management API, serving three sets of adminApp tests:
 *
 *  - The upload section ([org.example.project.admin.features.documents.presentation.DocumentUploadViewModel])
 *    asserts what `uploadDocument` was sent, via [uploadCalls] / [lastUpload]. [Upload.metadataArguments]
 *    carries every non-file argument `uploadDocument` accepts — all thirty invoice, DATEV-AP and
 *    bank-statement fields — so the section's "a file, a name and a type and **nothing else**" contract
 *    is assertable.
 *  - [org.example.project.admin.features.documents.presentation.DocumentsViewModel]'s concurrency tests
 *    and [org.example.project.admin.features.documents.presentation.DocumentDetailViewModel]'s tests drive
 *    [searchDocuments] plus the configurable detail-screen stubs.
 *  - [org.example.project.admin.features.documents.presentation.capture.InvoiceCaptureViewModel]'s tests
 *    drive the five normalized-invoice methods; [getDocumentInvoice] defaults to a 404 so the create path
 *    is reachable with no setup.
 *
 * [searchDocuments] responses are keyed by a `(fileType, processingStatuses)` pair rather than queued in
 * call order: racing requests need not reach [searchDocuments] in the order the ViewModel launched them
 * (a request cancelled before the dispatcher starts it never calls this at all), so an order-based queue
 * would silently hand a test's "first" response to whichever request runs first. The status half of the
 * key is a `List`, and List equality is order-sensitive — an expected key must list the statuses in
 * the same order the call under test sends them.
 */
class FakeDocumentManagementApiClient(
    private val documentId: Long = 77L,
    private val failWith: Throwable? = null,
) : DocumentManagementApiClient {
    // ===== Upload recording (DocumentUploadViewModel) =====
    data class Upload(
        val fileBytes: ByteArray,
        val fileName: String,
        val fileType: String,
        /** Every argument other than the file, its name and its type — all thirty of them. */
        val metadataArguments: List<String?>,
    )

    var uploadCalls: Int = 0
        private set

    /** The `unlockPassword` of the most recent upload; null when the field would not have been sent. */
    var lastUnlockPassword: String? = null

    /** Failures handed out one per upload, oldest first, before [failWith] is consulted. */
    val uploadFailures: ArrayDeque<Throwable> = ArrayDeque()

    var lastUpload: Upload? = null
        private set

    // ===== Search (DocumentsViewModel) =====
    data class Call(
        val fileType: String?,
        val processingStatuses: List<ProcessingStatus>?,
        val offset: Int,
    )

    private val responders =
        mutableMapOf<Pair<String?, List<ProcessingStatus>?>, suspend () -> DocumentDto.DocumentListResponseDto>()

    /** Every call actually made, in call order — a cancelled-before-start request never appears. */
    val calls = mutableListOf<Call>()

    val callCount: Int
        get() = calls.size

    fun respondTo(
        fileType: String?,
        processingStatuses: List<ProcessingStatus>? = null,
        produce: suspend () -> DocumentDto.DocumentListResponseDto,
    ) {
        responders[fileType to processingStatuses] = produce
    }

    // ===== Detail-screen configurable stubs =====

    var getDocumentByIdResponse: (suspend (Long) -> DocumentDto.DocumentResponseDto)? = null
    var getDocumentMetadataResponse: (suspend (Long) -> DocumentDto.DocumentMetadataDto)? = null
    var getDocumentFileResponse: (suspend (Long) -> ByteArray)? = null
    var updateBankStatementMetadataResponse:
        (suspend (Long, DocumentDto.UpdateBankStatementMetadataRequestDto) -> DocumentDto.DocumentResponseDto)? = null
    var deleteDocumentResponse: (suspend (Long) -> Unit)? = null

    var updateBankStatementMetadataCallCount = 0
        private set
    var deleteDocumentCallCount = 0
        private set

    /**
     * Counted because the invoice-keyed capture path must issue **neither** call: for an invoice with
     * no document both would 404, and a path that merely tolerated the failure would still spend two
     * doomed round-trips on every open.
     */
    var getDocumentByIdCallCount = 0
        private set
    var getDocumentFileCallCount = 0
        private set

    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    override suspend fun uploadDocument(
        fileBytes: ByteArray,
        fileName: String,
        fileType: String,
        description: String?,
        ocrText: String?,
        vendorName: String?,
        invoiceNumber: String?,
        invoiceDate: String?,
        dueDate: String?,
        netAmount: String?,
        grossAmount: String?,
        taxAmount: String?,
        taxRate: String?,
        currency: String?,
        iban: String?,
        paymentReference: String?,
        supplierCity: String?,
        vatId: String?,
        accountNo: String?,
        bpAccountNo: String?,
        deliveryDate: String?,
        bookingText: String?,
        taxLineItemsJson: String?,
        bankName: String?,
        bic: String?,
        accountNumber: String?,
        statementNumber: String?,
        statementDate: String?,
        periodStart: String?,
        periodEnd: String?,
        openingBalance: String?,
        closingBalance: String?,
        unlockPassword: String?,
    ): DocumentDto.DocumentResponseDto {
        uploadCalls++
        lastUnlockPassword = unlockPassword
        lastUpload =
            Upload(
                fileBytes = fileBytes,
                fileName = fileName,
                fileType = fileType,
                metadataArguments =
                    listOf(
                        description,
                        ocrText,
                        vendorName,
                        invoiceNumber,
                        invoiceDate,
                        dueDate,
                        netAmount,
                        grossAmount,
                        taxAmount,
                        taxRate,
                        currency,
                        iban,
                        paymentReference,
                        supplierCity,
                        vatId,
                        accountNo,
                        bpAccountNo,
                        deliveryDate,
                        bookingText,
                        taxLineItemsJson,
                        bankName,
                        bic,
                        accountNumber,
                        statementNumber,
                        statementDate,
                        periodStart,
                        periodEnd,
                        openingBalance,
                        closingBalance,
                    ),
            )
        uploadFailures.removeFirstOrNull()?.let { throw it }
        failWith?.let { throw it }
        return response(documentId, fileName, fileType)
    }

    /**
     * The parse outcome the next upload answers with, so a test can drive each [UploadOutcome].
     *
     * Defaults to the plain-scan case, which is what most uploads really are.
     */
    var uploadProcessingStatus: ProcessingStatus = ProcessingStatus.NONE

    /** The invoice the next upload reports as extracted, when it reports one at all. */
    var uploadInvoiceMetadata: DocumentDto.InvoiceMetadataDto? = null

    private fun response(
        id: Long,
        fileName: String,
        fileType: String,
    ) = DocumentDto.DocumentResponseDto(
        id = id,
        filename = fileName,
        fileType = fileType,
        fileSize = 1024,
        uploadedAt = "2026-08-10T09:00:00",
        uploadedBy = "employee-1",
        downloadUrl = "/api/documents/$id/file",
        formattedFileSize = "1.0 KB",
        monthFolder = "2026-08",
        processingStatus = uploadProcessingStatus,
        invoiceMetadata = uploadInvoiceMetadata,
    )

    override suspend fun getDocumentById(documentId: Long): DocumentDto.DocumentResponseDto {
        getDocumentByIdCallCount++
        return getDocumentByIdResponse?.invoke(documentId) ?: throw NotImplementedError()
    }

    override suspend fun getDocumentMetadata(documentId: Long): DocumentDto.DocumentMetadataDto =
        getDocumentMetadataResponse?.invoke(documentId) ?: throw NotImplementedError()

    override suspend fun getDocumentFile(documentId: Long): ByteArray {
        getDocumentFileCallCount++
        return getDocumentFileResponse?.invoke(documentId) ?: throw NotImplementedError()
    }

    @Suppress("LongParameterList")
    override suspend fun searchDocuments(
        fileType: String?,
        uploadedBy: String?,
        startDate: String?,
        endDate: String?,
        vendorName: String?,
        invoiceNumber: String?,
        minAmount: String?,
        maxAmount: String?,
        bankName: String?,
        statementNumber: String?,
        processingStatuses: List<ProcessingStatus>?,
        searchTerm: String?,
        includeInactive: Boolean,
        offset: Int,
        limit: Int,
    ): DocumentDto.DocumentListResponseDto {
        calls.add(Call(fileType, processingStatuses, offset))
        val produce =
            responders[fileType to processingStatuses]
                ?: error("no response configured for fileType=$fileType processingStatuses=$processingStatuses")
        return produce()
    }

    override suspend fun deleteDocument(documentId: Long) {
        deleteDocumentCallCount++
        deleteDocumentResponse?.invoke(documentId) ?: throw NotImplementedError()
    }

    override suspend fun updateBankStatementMetadata(
        documentId: Long,
        request: DocumentDto.UpdateBankStatementMetadataRequestDto,
    ): DocumentDto.DocumentResponseDto {
        updateBankStatementMetadataCallCount++
        return updateBankStatementMetadataResponse?.invoke(documentId, request) ?: throw NotImplementedError()
    }

    override suspend fun createDocumentLinks(request: DocumentDto.CreateDocumentLinkRequestDto): List<DocumentDto.DocumentLinkResponseDto> =
        throw NotImplementedError()

    override suspend fun getLinkedDocuments(documentId: Long): List<DocumentDto.DocumentLinkResponseDto> = throw NotImplementedError()

    override suspend fun deleteDocumentLink(linkId: Long): Unit = throw NotImplementedError()

    override suspend fun getUnlinkedDocuments(
        fileType: String?,
        search: String?,
        excludeTypes: List<String>,
        startDate: String?,
        endDate: String?,
    ): List<DocumentDto.DocumentResponseDto> = throw NotImplementedError()

    override suspend fun getDocumentTypes(): List<String> = throw NotImplementedError()

    override suspend fun getStatistics(): DocumentDto.DocumentStorageStatisticsDto = throw NotImplementedError()

    override suspend fun exportToDatevAp(
        startDate: String,
        endDate: String,
        periodLabel: String,
        excludeKassenbuchLinked: Boolean,
    ): DatevExportLogDto = throw NotImplementedError()

    // ===== Normalized invoice aggregate (Stage 3c-3) =====

    /**
     * Defaults to throwing `RESOURCE_NOT_FOUND`, because that is what the server does for a document
     * with no invoice — the normal state of a freshly scanned PDF. A test that wants the create path
     * therefore configures nothing, and the ViewModel's 404-means-create branch is the default.
     */
    var getDocumentInvoiceResponse: (suspend (Long) -> DocumentDto.InvoiceDto) = {
        throw PosApiException(
            code = PosErrorCode.RESOURCE_NOT_FOUND,
            httpStatus = 404,
            message = "No invoice for document $it",
            traceId = null,
        )
    }
    var createDocumentInvoiceResponse:
        (suspend (Long, DocumentDto.InvoiceContentRequestDto) -> DocumentDto.InvoiceDto)? = null
    var updateInvoiceContentResponse:
        (suspend (Long, DocumentDto.InvoiceContentRequestDto) -> DocumentDto.InvoiceDto)? = null
    var updateInvoiceBookingResponse:
        (suspend (Long, DocumentDto.UpdateInvoiceBookingRequestDto) -> DocumentDto.InvoiceDto)? = null
    var updateDocumentStatusResponse:
        (suspend (Long, DocumentDto.UpdateDocumentStatusRequestDto) -> DocumentDto.DocumentResponseDto)? = null

    var cancelInvoiceResponse:
        (suspend (Long, DocumentDto.CancelInvoiceRequestDto) -> DocumentDto.InvoiceDto)? = null

    /** Every cancel request, in call order, keyed by invoice id. */
    val cancelRequests = mutableListOf<Pair<Long, DocumentDto.CancelInvoiceRequestDto>>()

    var createDocumentInvoiceCallCount = 0
        private set
    var updateInvoiceContentCallCount = 0
        private set
    var updateInvoiceBookingCallCount = 0
        private set

    /** Every status write, in call order — the workflow transitions are asserted as a sequence. */
    val statusWrites = mutableListOf<ProcessingStatus>()

    override suspend fun getDocumentInvoice(documentId: Long): DocumentDto.InvoiceDto = getDocumentInvoiceResponse(documentId)

    override suspend fun createDocumentInvoice(
        documentId: Long,
        request: DocumentDto.InvoiceContentRequestDto,
    ): DocumentDto.InvoiceDto {
        createDocumentInvoiceCallCount++
        val produce = createDocumentInvoiceResponse ?: error("createDocumentInvoiceResponse not set")
        return produce(documentId, request)
    }

    override suspend fun updateInvoiceContent(
        invoiceId: Long,
        request: DocumentDto.InvoiceContentRequestDto,
    ): DocumentDto.InvoiceDto {
        updateInvoiceContentCallCount++
        val produce = updateInvoiceContentResponse ?: error("updateInvoiceContentResponse not set")
        return produce(invoiceId, request)
    }

    override suspend fun updateInvoiceBooking(
        invoiceId: Long,
        request: DocumentDto.UpdateInvoiceBookingRequestDto,
    ): DocumentDto.InvoiceDto {
        updateInvoiceBookingCallCount++
        val produce = updateInvoiceBookingResponse ?: error("updateInvoiceBookingResponse not set")
        return produce(invoiceId, request)
    }

    override suspend fun cancelInvoice(
        invoiceId: Long,
        request: DocumentDto.CancelInvoiceRequestDto,
    ): DocumentDto.InvoiceDto {
        cancelRequests.add(invoiceId to request)
        val produce = cancelInvoiceResponse ?: error("cancelInvoiceResponse not set")
        return produce(invoiceId, request)
    }

    override suspend fun updateDocumentStatus(
        documentId: Long,
        request: DocumentDto.UpdateDocumentStatusRequestDto,
    ): DocumentDto.DocumentResponseDto {
        statusWrites.add(request.status)
        val produce = updateDocumentStatusResponse ?: error("updateDocumentStatusResponse not set")
        return produce(documentId, request)
    }
}
