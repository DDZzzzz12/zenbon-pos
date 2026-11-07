package org.example.project.testutils.fakes

import org.example.project.core.data.remote.DocumentManagementApiClient
import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.ProcessingStatus

class FakeDocumentManagementApiClient : DocumentManagementApiClient {
    var uploadDocumentResult: DocumentDto.DocumentResponseDto? = null
    var getDocumentByIdResult: DocumentDto.DocumentResponseDto? = null
    var getDocumentMetadataResult: DocumentDto.DocumentMetadataDto? = null
    var getDocumentFileResult: ByteArray = ByteArray(0)
    var searchDocumentsResult: DocumentDto.DocumentListResponseDto =
        DocumentDto.DocumentListResponseDto(items = emptyList(), total = 0, offset = 0, limit = 50, hasMore = false)
    var updateBankStatementMetadataResult: DocumentDto.DocumentResponseDto? = null
    var createDocumentLinksResult: List<DocumentDto.DocumentLinkResponseDto> = emptyList()
    var getLinkedDocumentsResult: List<DocumentDto.DocumentLinkResponseDto> = emptyList()
    var getUnlinkedDocumentsResult: List<DocumentDto.DocumentResponseDto> = emptyList()
    var getDocumentTypesResult: List<String> = emptyList()
    var getStatisticsResult: DocumentDto.DocumentStorageStatisticsDto =
        DocumentDto.DocumentStorageStatisticsDto(
            totalDocuments = 0,
            invoiceCount = 0,
            bankStatementCount = 0,
            contractsCount = 0,
            customCount = 0,
            totalStorageBytes = 0,
            formattedTotalStorage = "0 B",
        )
    var exportToDatevApResult: DatevExportLogDto? = null
    var getDocumentInvoiceResult: DocumentDto.InvoiceDto? = null
    var createDocumentInvoiceResult: DocumentDto.InvoiceDto? = null
    var updateInvoiceContentResult: DocumentDto.InvoiceDto? = null
    var updateInvoiceBookingResult: DocumentDto.InvoiceDto? = null
    var updateDocumentStatusResult: DocumentDto.DocumentResponseDto? = null

    var uploadDocumentThrows: Throwable? = RuntimeException("server error")
    var getDocumentByIdThrows: Throwable? = RuntimeException("not found")
    var getDocumentMetadataThrows: Throwable? = RuntimeException("not set")
    var getDocumentFileThrows: Throwable? = null
    var searchDocumentsThrows: Throwable? = null
    var deleteDocumentThrows: Throwable? = null
    var updateBankStatementMetadataThrows: Throwable? = RuntimeException("server error")
    var createDocumentLinksThrows: Throwable? = null
    var getLinkedDocumentsThrows: Throwable? = null
    var deleteDocumentLinkThrows: Throwable? = null
    var getUnlinkedDocumentsThrows: Throwable? = null
    var getDocumentTypesThrows: Throwable? = null
    var getStatisticsThrows: Throwable? = null
    var exportToDatevApThrows: Throwable? = RuntimeException("server error")
    var getDocumentInvoiceThrows: Throwable? = RuntimeException("not found")
    var createDocumentInvoiceThrows: Throwable? = RuntimeException("server error")
    var updateInvoiceContentThrows: Throwable? = RuntimeException("server error")
    var updateInvoiceBookingThrows: Throwable? = RuntimeException("server error")
    var updateDocumentStatusThrows: Throwable? = RuntimeException("server error")

    var uploadDocumentCallCount = 0
    var searchDocumentsCallCount = 0

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
        uploadDocumentCallCount++
        uploadDocumentThrows?.let { throw it }
        return uploadDocumentResult ?: error("uploadDocumentResult not set")
    }

    override suspend fun getDocumentById(documentId: Long): DocumentDto.DocumentResponseDto {
        getDocumentByIdThrows?.let { throw it }
        return getDocumentByIdResult ?: error("getDocumentByIdResult not set")
    }

    override suspend fun getDocumentMetadata(documentId: Long): DocumentDto.DocumentMetadataDto {
        getDocumentMetadataThrows?.let { throw it }
        return getDocumentMetadataResult ?: error("getDocumentMetadataResult not set")
    }

    override suspend fun getDocumentFile(documentId: Long): ByteArray {
        getDocumentFileThrows?.let { throw it }
        return getDocumentFileResult
    }

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
        searchDocumentsCallCount++
        searchDocumentsThrows?.let { throw it }
        return searchDocumentsResult
    }

    override suspend fun deleteDocument(documentId: Long) {
        deleteDocumentThrows?.let { throw it }
    }

    override suspend fun updateBankStatementMetadata(
        documentId: Long,
        request: DocumentDto.UpdateBankStatementMetadataRequestDto,
    ): DocumentDto.DocumentResponseDto {
        updateBankStatementMetadataThrows?.let { throw it }
        return updateBankStatementMetadataResult ?: error("updateBankStatementMetadataResult not set")
    }

    override suspend fun createDocumentLinks(request: DocumentDto.CreateDocumentLinkRequestDto): List<DocumentDto.DocumentLinkResponseDto> {
        createDocumentLinksThrows?.let { throw it }
        return createDocumentLinksResult
    }

    override suspend fun getLinkedDocuments(documentId: Long): List<DocumentDto.DocumentLinkResponseDto> {
        getLinkedDocumentsThrows?.let { throw it }
        return getLinkedDocumentsResult
    }

    override suspend fun deleteDocumentLink(linkId: Long) {
        deleteDocumentLinkThrows?.let { throw it }
    }

    override suspend fun getUnlinkedDocuments(
        fileType: String?,
        search: String?,
        excludeTypes: List<String>,
        startDate: String?,
        endDate: String?,
    ): List<DocumentDto.DocumentResponseDto> {
        getUnlinkedDocumentsThrows?.let { throw it }
        return getUnlinkedDocumentsResult
    }

    override suspend fun getDocumentTypes(): List<String> {
        getDocumentTypesThrows?.let { throw it }
        return getDocumentTypesResult
    }

    override suspend fun getStatistics(): DocumentDto.DocumentStorageStatisticsDto {
        getStatisticsThrows?.let { throw it }
        return getStatisticsResult
    }

    override suspend fun exportToDatevAp(
        startDate: String,
        endDate: String,
        periodLabel: String,
        excludeKassenbuchLinked: Boolean,
    ): DatevExportLogDto {
        exportToDatevApThrows?.let { throw it }
        return exportToDatevApResult ?: error("exportToDatevApResult not set")
    }

    override suspend fun getDocumentInvoice(documentId: Long): DocumentDto.InvoiceDto {
        getDocumentInvoiceThrows?.let { throw it }
        return getDocumentInvoiceResult ?: error("getDocumentInvoiceResult not set")
    }

    override suspend fun createDocumentInvoice(
        documentId: Long,
        request: DocumentDto.InvoiceContentRequestDto,
    ): DocumentDto.InvoiceDto {
        createDocumentInvoiceThrows?.let { throw it }
        return createDocumentInvoiceResult ?: error("createDocumentInvoiceResult not set")
    }

    override suspend fun updateInvoiceContent(
        invoiceId: Long,
        request: DocumentDto.InvoiceContentRequestDto,
    ): DocumentDto.InvoiceDto {
        updateInvoiceContentThrows?.let { throw it }
        return updateInvoiceContentResult ?: error("updateInvoiceContentResult not set")
    }

    override suspend fun updateInvoiceBooking(
        invoiceId: Long,
        request: DocumentDto.UpdateInvoiceBookingRequestDto,
    ): DocumentDto.InvoiceDto {
        updateInvoiceBookingThrows?.let { throw it }
        return updateInvoiceBookingResult ?: error("updateInvoiceBookingResult not set")
    }

    override suspend fun cancelInvoice(
        invoiceId: Long,
        request: DocumentDto.CancelInvoiceRequestDto,
    ): DocumentDto.InvoiceDto = error("cancelInvoice is not used by composeApp")

    override suspend fun updateDocumentStatus(
        documentId: Long,
        request: DocumentDto.UpdateDocumentStatusRequestDto,
    ): DocumentDto.DocumentResponseDto {
        updateDocumentStatusThrows?.let { throw it }
        return updateDocumentStatusResult ?: error("updateDocumentStatusResult not set")
    }
}
