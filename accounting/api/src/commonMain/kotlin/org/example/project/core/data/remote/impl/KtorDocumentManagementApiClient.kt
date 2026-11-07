/**
 * Ktor-based implementation of document management API client.
 *
 * Stage C Batch 8: throws-style.
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import org.example.project.core.data.remote.DocumentManagementApiClient
import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorDocumentManagementApiClient(
    private val httpCall: HttpCall,
) : DocumentManagementApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/documents"

        /** `/api/invoices` is its own top-level route group — see `InvoiceRoutes.kt`. */
        private const val INVOICE_ENDPOINT = "api/invoices"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

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
        val fileContentType =
            when {
                fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
                fileName.endsWith(".png", true) -> "image/png"
                else -> "application/pdf"
            }

        return httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/upload")) {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            // File part
                            append(
                                "file",
                                fileBytes,
                                Headers.build {
                                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                                    append(HttpHeaders.ContentType, fileContentType)
                                },
                            )

                            // Common fields
                            append("fileType", fileType)
                            description?.let { append("description", it) }
                            ocrText?.let { append("ocrText", it) }

                            // Invoice-specific
                            vendorName?.let { append("vendorName", it) }
                            invoiceNumber?.let { append("invoiceNumber", it) }
                            invoiceDate?.let { append("invoiceDate", it) }
                            dueDate?.let { append("dueDate", it) }
                            netAmount?.let { append("netAmount", it) }
                            grossAmount?.let { append("grossAmount", it) }
                            taxAmount?.let { append("taxAmount", it) }
                            taxRate?.let { append("taxRate", it) }
                            currency?.let { append("currency", it) }
                            iban?.let { append("iban", it) }
                            paymentReference?.let { append("paymentReference", it) }

                            // DATEV AP fields
                            supplierCity?.let { append("supplierCity", it) }
                            vatId?.let { append("vatId", it) }
                            accountNo?.let { append("accountNo", it) }
                            bpAccountNo?.let { append("bpAccountNo", it) }
                            deliveryDate?.let { append("deliveryDate", it) }
                            bookingText?.let { append("bookingText", it) }

                            taxLineItemsJson?.let { append("taxLineItemsJson", it) }

                            // Bank statement-specific
                            bankName?.let { append("bankName", it) }
                            bic?.let { append("bic", it) }
                            accountNumber?.let { append("accountNumber", it) }
                            statementNumber?.let { append("statementNumber", it) }
                            statementDate?.let { append("statementDate", it) }
                            periodStart?.let { append("periodStart", it) }
                            periodEnd?.let { append("periodEnd", it) }
                            openingBalance?.let { append("openingBalance", it) }
                            closingBalance?.let { append("closingBalance", it) }
                            // Never logged or echoed; sent only in the multipart body.
                            unlockPassword?.let { append("unlockPassword", it) }
                        },
                    ),
                )
            }
        }
    }

    override suspend fun getDocumentById(documentId: Long): DocumentDto.DocumentResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/$documentId"))
        }

    override suspend fun getDocumentFile(documentId: Long): ByteArray =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/$documentId/file"))
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
    ): DocumentDto.DocumentListResponseDto =
        httpCall.httpClient.apiCall {
            get(url(BASE_ENDPOINT)) {
                fileType?.let { parameter("fileType", it) }
                uploadedBy?.let { parameter("uploadedBy", it) }
                startDate?.let { parameter("fromDate", it) }
                endDate?.let { parameter("toDate", it) }
                vendorName?.let { parameter("vendorName", it) }
                invoiceNumber?.let { parameter("invoiceNumber", it) }
                minAmount?.let { parameter("minAmount", it) }
                maxAmount?.let { parameter("maxAmount", it) }
                bankName?.let { parameter("bankName", it) }
                statementNumber?.let { parameter("statementNumber", it) }
                // Comma-separated under the singular wire name the route parses. `.name`, not
                // `toString()` — the route matches on the enum's constant name, which is also what
                // the column stores. An empty list sends nothing, matching "no filter".
                processingStatuses?.takeIf { it.isNotEmpty() }?.let { statuses ->
                    parameter("processingStatus", statuses.joinToString(",") { it.name })
                }
                searchTerm?.let { parameter("searchTerm", it) }
                if (includeInactive) parameter("activeOnly", "false")
                parameter("offset", offset.toString())
                parameter("limit", limit.toString())
            }
        }

    override suspend fun deleteDocument(documentId: Long) {
        httpCall.httpClient.apiCall<Unit> { delete(url("$BASE_ENDPOINT/$documentId")) }
    }

    override suspend fun updateBankStatementMetadata(
        documentId: Long,
        request: DocumentDto.UpdateBankStatementMetadataRequestDto,
    ): DocumentDto.DocumentResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$documentId/bank-statement-metadata")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun createDocumentLinks(request: DocumentDto.CreateDocumentLinkRequestDto): List<DocumentDto.DocumentLinkResponseDto> =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/link")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun getLinkedDocuments(documentId: Long): List<DocumentDto.DocumentLinkResponseDto> =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/$documentId/linked"))
        }

    override suspend fun deleteDocumentLink(linkId: Long) {
        httpCall.httpClient.apiCall<Unit> { delete(url("$BASE_ENDPOINT/link/$linkId")) }
    }

    override suspend fun getDocumentMetadata(documentId: Long): DocumentDto.DocumentMetadataDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/$documentId/metadata"))
        }

    override suspend fun getUnlinkedDocuments(
        fileType: String?,
        search: String?,
        excludeTypes: List<String>,
        startDate: String?,
        endDate: String?,
    ): List<DocumentDto.DocumentResponseDto> =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/unlinked")) {
                if (fileType != null) parameter("fileType", fileType)
                if (!search.isNullOrBlank()) parameter("search", search)
                if (excludeTypes.isNotEmpty()) parameter("excludeTypes", excludeTypes.joinToString(","))
                if (startDate != null) parameter("startDate", startDate)
                if (endDate != null) parameter("endDate", endDate)
            }
        }

    override suspend fun getDocumentTypes(): List<String> {
        val response: DocumentDto.DocumentTypesResponseDto =
            httpCall.httpClient.apiCall {
                get(url("$BASE_ENDPOINT/document-types"))
            }
        return response.types
    }

    override suspend fun getStatistics(): DocumentDto.DocumentStorageStatisticsDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/statistics")) }

    override suspend fun exportToDatevAp(
        startDate: String,
        endDate: String,
        periodLabel: String,
        excludeKassenbuchLinked: Boolean,
    ): DatevExportLogDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/export/datev-ap")) {
                parameter("startDate", startDate)
                parameter("endDate", endDate)
                if (periodLabel.isNotBlank()) parameter("periodLabel", periodLabel)
                parameter("excludeKassenbuchLinked", excludeKassenbuchLinked.toString())
            }
        }

    override suspend fun getDocumentInvoice(documentId: Long): DocumentDto.InvoiceDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/$documentId/invoice"))
        }

    override suspend fun createDocumentInvoice(
        documentId: Long,
        request: DocumentDto.InvoiceContentRequestDto,
    ): DocumentDto.InvoiceDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/$documentId/invoice")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updateInvoiceContent(
        invoiceId: Long,
        request: DocumentDto.InvoiceContentRequestDto,
    ): DocumentDto.InvoiceDto =
        httpCall.httpClient.apiCall {
            put(url("$INVOICE_ENDPOINT/$invoiceId")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updateInvoiceBooking(
        invoiceId: Long,
        request: DocumentDto.UpdateInvoiceBookingRequestDto,
    ): DocumentDto.InvoiceDto =
        httpCall.httpClient.apiCall {
            put(url("$INVOICE_ENDPOINT/$invoiceId/booking")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun cancelInvoice(
        invoiceId: Long,
        request: DocumentDto.CancelInvoiceRequestDto,
    ): DocumentDto.InvoiceDto =
        httpCall.httpClient.apiCall {
            post(url("$INVOICE_ENDPOINT/$invoiceId/cancel")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updateDocumentStatus(
        documentId: Long,
        request: DocumentDto.UpdateDocumentStatusRequestDto,
    ): DocumentDto.DocumentResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$documentId/status")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
}
