/**
 * Document Management API Routes
 *
 * REST API endpoints for document management: upload, retrieval, search, and linking.
 * Supports multi-format files (PDF, JPEG, PNG).
 *
 * CRITICAL AUTHENTICATION PATTERN:
 * - Single authenticate("auth-jwt") wrapper for entire route group
 * - Prevents "Default authentication configuration was not found" runtime error
 * - Never nest multiple authenticate blocks
 *
 * Endpoints:
 * - POST /api/documents/upload - Upload document
 * - GET /api/documents/{id} - Get document metadata by ID
 * - GET /api/documents/{id}/metadata - Get document metadata only (no file bytes)
 * - GET /api/documents/{id}/file - Serve document file for viewing/download
 * - GET /api/documents - Search documents with filters and pagination
 * - DELETE /api/documents/{id} - Soft delete document
 * - PUT /api/documents/{id}/metadata - Update document metadata
 * - GET /api/documents/statistics - Get storage statistics
 * - POST /api/documents/link - Create document links (invoice -> payment)
 * - GET /api/documents/{id}/linked - Get linked documents
 * - DELETE /api/documents/link/{linkId} - Remove document link
 * - GET /api/documents/unlinked - Get unlinked documents
 * - PUT /api/documents/{id}/bank-statement-metadata - Update bank statement metadata
 * - GET /api/documents/{id}/invoice - Read the normalized invoice aggregate
 * - POST /api/documents/{id}/invoice - Create a hand-entered invoice (MANUAL provenance)
 * - PUT /api/documents/{id}/status - Set the operator workflow status
 *
 * Throws PosException for parameter-validation failures; the global StatusPages
 * handler converts PosException (and service-thrown PosException) to
 * application/problem+json. Migrated from Result<T, Error> in Stage B Batch 6.
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.document.domain.routes

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.DocumentType
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.core.domain.enums.setting.DatevExportSource
import org.example.project.core.error.PosErrorCode
import org.example.project.core.security.UnauthorizedException
import org.example.project.core.security.getEmployeeIdFromJwt
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.document.domain.service.DocumentManagementService
import org.example.project.features.document.domain.service.PdfGenerationService
import org.example.project.features.kassenbuch.domain.service.DatevApExportService
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.requireIsoDate
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

private val logger = LoggerFactory.getLogger("DocumentRoutes")

/**
 * Document Management API routes using single authentication wrapper.
 *
 * All endpoints require JWT authentication with appropriate role permissions.
 */
fun Route.documentRoutes() {
    val documentManagementService by inject<DocumentManagementService>()
    val pdfGenerationService by inject<PdfGenerationService>()
    val billService by inject<BillService>()
    val datevApExportService by inject<DatevApExportService>()
    val configManager by inject<IShopConfigurationManager>()

    // CRITICAL: Single authenticate wrapper for all endpoints
    authenticate("auth-jwt") {
        route("/api/documents") {
            /**
             * List the available document types.
             *
             * Description: The built-in types plus any extra folders the operator configured under document storage, de-duplicated. This is the set the upload endpoint accepts as fileType.
             *
             * Tag: documents
             * Response: 401 Missing or invalid bearer token.
             */
            get("/document-types") {
                val enumTypes = DocumentType.entries.map { it.name }
                val additionalTypes = configManager.getAdditionalDocumentTypes()
                val allTypes = (enumTypes + additionalTypes).distinct()
                call.respond(HttpStatusCode.OK, DocumentDto.DocumentTypesResponseDto(types = allTypes))
            }

            /**
             * Upload a document.
             *
             * Description: Requires MANAGER role. Send the file as a multipart upload with a `file` part; `fileType` is required and every other field is an optional form field. `fileType` selects the document kind, one of the types listed by the document types endpoint, and the type-specific fields are carried alongside — vendor, invoice number, dates and amounts for an invoice, plus the DATEV supplier and account fields, or bank, IBAN, statement number and balances for a bank statement. Money fields travel as decimal strings. A malformed taxLineItemsJson is dropped rather than failing the upload. The uploading employee is taken from the JWT. A CAMT.053 or MT940 file is parsed into a bank statement on the spot; the response's `statementImportNotices` says when its balances do not reconcile (the statement is refused and the document is marked FAILED, the file stays archived), when it overlaps an already imported statement, or — for an upload declared as `BANK_STATEMENT` — that the file carries no camt.053 or MT940 at all (NOT_A_BANK_STATEMENT; nothing imported, the file is archived as an ordinary document). To store a password-protected PDF unlocked, add an `unlockPassword` form field with its password (an empty value for a PDF that only restricts editing); the server removes the protection and archives the unlocked copy, which is what hashing, duplicate detection and e-invoice parsing then see. Omit the field to archive the PDF exactly as sent.
             *
             * Tag: documents
             * Response: 400 No file part or no fileType was supplied, the employee id is not a valid UUID, or a metadata field failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 413 The uploaded file exceeds the configured upload size limit.
             * Response: 422 `unlockPassword` was sent but does not open the PDF (PDF_PASSWORD_INCORRECT), or was empty for a PDF that needs a password (PDF_PASSWORD_REQUIRED). Nothing is stored.
             * Response: 500 The file could not be written to the document store.
             */
            post("/upload") {
                val uploadedBy = call.getEmployeeIdFromJwt() ?: "SYSTEM"

                val multipart = call.receiveMultipart()
                var uploadFile: File? = null
                var filename: String? = null
                var fileType: String? = null
                var description: String? = null
                var metadata: String? = null
                var ocrText: String? = null
                // Present (even empty) = unlock a password-protected PDF before storing it. Never logged.
                var unlockPassword: String? = null

                // Invoice-specific fields
                var vendorName: String? = null
                var invoiceNumber: String? = null
                var invoiceDate: String? = null
                var dueDate: String? = null
                var netAmount: String? = null
                var grossAmount: String? = null
                var taxAmount: String? = null
                var taxRate: String? = null
                var currency: String? = null
                var iban: String? = null
                var paymentReference: String? = null
                var taxLineItemsJson: String? = null

                // DATEV AP (Eingangsrechnungen) fields
                var supplierCity: String? = null
                var vatId: String? = null
                var accountNo: String? = null
                var bpAccountNo: String? = null
                var deliveryDate: String? = null
                var bookingText: String? = null

                // Bank statement-specific fields
                var bankName: String? = null
                var bic: String? = null
                var accountNumber: String? = null
                var statementNumber: String? = null
                var statementDate: String? = null
                var periodStart: String? = null
                var periodEnd: String? = null
                var openingBalance: String? = null
                var closingBalance: String? = null

                // Parse multipart form data
                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> {
                            if (part.name == "file") {
                                filename = part.originalFileName
                                val fileBytes = part.provider().readRemaining().readByteArray()
                                uploadFile =
                                    File.createTempFile("upload_", ".tmp").also {
                                        it.writeBytes(fileBytes)
                                    }
                            }
                        }
                        is PartData.FormItem -> {
                            when (part.name) {
                                "fileType" -> fileType = part.value
                                "description" -> description = part.value
                                "metadata" -> metadata = part.value
                                "ocrText" -> ocrText = part.value
                                "unlockPassword" -> unlockPassword = part.value
                                "vendorName" -> vendorName = part.value
                                "invoiceNumber" -> invoiceNumber = part.value
                                "invoiceDate" -> invoiceDate = part.value
                                "dueDate" -> dueDate = part.value
                                "netAmount" -> netAmount = part.value.takeIf { it.isNotBlank() }
                                "grossAmount" -> grossAmount = part.value.takeIf { it.isNotBlank() }
                                "taxAmount" -> taxAmount = part.value.takeIf { it.isNotBlank() }
                                "taxRate" -> taxRate = part.value.takeIf { it.isNotBlank() }
                                "currency" -> currency = part.value
                                "iban" -> iban = part.value
                                "paymentReference" -> paymentReference = part.value
                                "taxLineItemsJson" -> taxLineItemsJson = part.value
                                "supplierCity" -> supplierCity = part.value
                                "vatId" -> vatId = part.value
                                "accountNo" -> accountNo = part.value
                                "bpAccountNo" -> bpAccountNo = part.value
                                "deliveryDate" -> deliveryDate = part.value
                                "bookingText" -> bookingText = part.value
                                "bankName" -> bankName = part.value
                                "bic" -> bic = part.value
                                "accountNumber" -> accountNumber = part.value
                                "statementNumber" -> statementNumber = part.value
                                "statementDate" -> statementDate = part.value
                                "periodStart" -> periodStart = part.value
                                "periodEnd" -> periodEnd = part.value
                                "openingBalance" -> openingBalance = part.value.takeIf { it.isNotBlank() }
                                "closingBalance" -> closingBalance = part.value.takeIf { it.isNotBlank() }
                            }
                        }
                        else -> {}
                    }
                    part.dispose()
                }

                if (uploadFile == null || filename == null) {
                    throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.BadRequest,
                        "File is required",
                    )
                }

                val requiredFileType = fileType?.takeIf { it.isNotBlank() }
                if (requiredFileType == null) {
                    // The try/finally that cleans the temp file comes after this refusal, so delete it here.
                    uploadFile?.delete()
                    throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.BadRequest,
                        "fileType is required; send one of the types listed by the document types endpoint",
                    )
                }

                val request =
                    DocumentDto.UploadDocumentRequestDto(
                        filename = filename!!,
                        fileType = requiredFileType,
                        description = description,
                        metadata = metadata,
                        ocrText = ocrText,
                        vendorName = vendorName,
                        invoiceNumber = invoiceNumber,
                        invoiceDate = invoiceDate,
                        dueDate = dueDate,
                        netAmount = netAmount,
                        grossAmount = grossAmount,
                        taxAmount = taxAmount,
                        taxRate = taxRate,
                        currency = currency,
                        iban = iban,
                        paymentReference = paymentReference,
                        supplierCity = supplierCity,
                        vatId = vatId,
                        accountNo = accountNo,
                        bpAccountNo = bpAccountNo,
                        deliveryDate = deliveryDate,
                        bookingText = bookingText,
                        taxLineItems =
                            taxLineItemsJson?.let { json ->
                                try {
                                    kotlinx.serialization.json.Json
                                        .decodeFromString<List<DocumentDto.TaxLineItemDto>>(json)
                                } catch (_: Exception) {
                                    null
                                }
                            },
                        bankName = bankName,
                        bic = bic,
                        accountNumber = accountNumber,
                        statementNumber = statementNumber,
                        statementDate = statementDate,
                        periodStart = periodStart,
                        periodEnd = periodEnd,
                        openingBalance = openingBalance,
                        closingBalance = closingBalance,
                    )

                val uploadedByUuid =
                    try {
                        UUID.fromString(uploadedBy)
                    } catch (e: IllegalArgumentException) {
                        throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid employee UUID",
                        )
                    }

                try {
                    val response =
                        documentManagementService.uploadDocument(
                            uploadFile!!,
                            request,
                            uploadedByUuid,
                            unlockPassword,
                        )
                    call.respond(HttpStatusCode.Created, response)
                } finally {
                    uploadFile?.delete()
                }
            }

            /**
             * Get a document by ID.
             *
             * Description: Returns the document record with its metadata. The file bytes are served by the file endpoint instead. `allocationCount` is the number of bank transactions allocated to the document's invoice (incoming: its payment allocations; outgoing: those of the sales invoice this document is the PDF of), distinct by transaction; a reversed allocation is deleted, so it is not counted. `linkCount` is a different thing (document and Kassenbuch links, including the file's own) and says nothing about payment. Write responses (upload, status) return `allocationCount` 0.
             *
             * Tag: documents
             * Path: id Document identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No document with that id.
             */
            get("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid document ID",
                        )
                val response = documentManagementService.getDocumentById(id)
                call.respond(HttpStatusCode.OK, response)
            }

            /**
             * Get a document's metadata.
             *
             * Description: The metadata alone, without the record's file details — the cheap read for a list or detail panel.
             *
             * Tag: documents
             * Path: id Document identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No document with that id.
             */
            get("/{id}/metadata") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid document ID",
                        )
                val response = documentManagementService.getDocumentMetadata(id)
                call.respond(HttpStatusCode.OK, response)
            }

            /**
             * Download a document's file.
             *
             * Description: Serves the stored bytes with an inline content disposition, so a browser renders a PDF rather than downloading it.
             *
             * Tag: documents
             * Path: id Document identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No document with that id, or its file is missing from the document store.
             */
            get("/{id}/file") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid document ID",
                        )
                val file = documentManagementService.getDocumentFile(id)
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Inline
                        .withParameter(
                            ContentDisposition.Parameters.FileName,
                            file.name,
                        ).toString(),
                )
                call.respondFile(file)
            }

            /**
             * Search documents.
             *
             * Description: Every filter is an optional query parameter — `fileType`, `fromDate`, `toDate`, `month`, `uploadedBy`, `vendorName`, `invoiceNumber`, `minAmount` and `maxAmount` as decimal strings, `bankName`, `statementNumber`, a comma-separated `processingStatus` (any of NONE, PENDING, PROCESSING, COMPLETED, FAILED, UNPROCESSABLE, IN_PROGRESS, REVIEWED; several are OR-ed, an unknown one is a 400), and a free-text `searchTerm`. The period filters (`fromDate`, `toDate`, `month`) apply to the document date shown in the list's Datum column — the invoice or bank-statement date — and to the upload date for a document that has none, such as a plain scan. `activeOnly` defaults to true, so soft-deleted documents are hidden unless it is set false. Pages with `offset` (default 0) and `limit` (default 50). Each row carries `hasInvoice` (a normalized invoice row exists, not the legacy metadata fallback) and `allocationCount` (bank transactions allocated to its invoice, reversals excluded; see the detail endpoint), both resolved for the whole page in batched queries.
             *
             * Tag: documents
             * Response: 401 Missing or invalid bearer token.
             */
            get {
                val filter =
                    DocumentDto.DocumentSearchFilterDto(
                        fileType = call.request.queryParameters["fileType"],
                        fromDate = call.request.queryParameters["fromDate"],
                        toDate = call.request.queryParameters["toDate"],
                        month = call.request.queryParameters["month"],
                        uploadedBy = call.request.queryParameters["uploadedBy"],
                        activeOnly = call.request.queryParameters["activeOnly"]?.toBoolean() ?: true,
                        processingStatuses =
                            call.request.queryParameters["processingStatus"]
                                ?.let(::parseProcessingStatuses),
                        vendorName = call.request.queryParameters["vendorName"],
                        invoiceNumber = call.request.queryParameters["invoiceNumber"],
                        minAmount = call.request.queryParameters["minAmount"]?.takeIf { it.isNotBlank() },
                        maxAmount = call.request.queryParameters["maxAmount"]?.takeIf { it.isNotBlank() },
                        bankName = call.request.queryParameters["bankName"],
                        statementNumber = call.request.queryParameters["statementNumber"],
                        searchTerm = call.request.queryParameters["searchTerm"],
                        offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0,
                        limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50,
                    )

                val response = documentManagementService.searchDocuments(filter)
                call.respond(HttpStatusCode.OK, response)
            }

            /**
             * Delete a document.
             *
             * Description: A soft delete — the record is flagged inactive and drops out of the default search, but the stored file and any links survive, because a document may be evidence for a booked cash-book entry.
             *
             * Tag: documents
             * Path: id Document identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No document with that id.
             */
            delete("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid document ID",
                        )
                documentManagementService.deleteDocument(id)
                call.respond(HttpStatusCode.OK, mapOf("message" to "Document deleted successfully"))
            }

            /**
             * Get document storage statistics.
             *
             * Description: Document counts and bytes used per type, for the storage panel.
             *
             * Tag: documents
             * Response: 401 Missing or invalid bearer token.
             */
            get("/statistics") {
                val response = documentManagementService.getStorageStatistics()
                call.respond(HttpStatusCode.OK, response)
            }

            /**
             * Update a document's free-form metadata.
             *
             * Description: Replaces the opaque `metadata` string on the record. Type-specific fields have their own endpoints — this one does not touch them.
             *
             * Tag: documents
             * Path: id Document identifier.
             * Response: 400 id is not a number, or the body carries no metadata field.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No document with that id.
             */
            put("/{id}/metadata") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid document ID",
                        )

                val requestBody = call.receive<Map<String, String>>()
                val metadata =
                    requestBody["metadata"]
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Metadata is required",
                        )

                val response = documentManagementService.updateMetadata(id, metadata)
                call.respond(HttpStatusCode.OK, response)
            }

            // ===== DOCUMENT LINKING ENDPOINTS =====

            /**
             * Link documents together.
             *
             * Description: Requires MANAGER role. Records a link — an invoice to the payment that settled it, for instance — so the pair can be followed in either direction. The linking employee is taken from the JWT. Several links may be created in one call.
             *
             * Tag: documents
             * Response: 400 The body failed validation, or the employee id is not a valid UUID.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 One of the referenced documents does not exist.
             * Response: 409 That link already exists.
             */
            post("/link") {
                val linkedByStr =
                    call.getEmployeeIdFromJwt()
                        ?: throw UnauthorizedException("Employee not found")
                val linkedByUuid =
                    try {
                        UUID.fromString(linkedByStr)
                    } catch (e: IllegalArgumentException) {
                        throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid employee UUID",
                        )
                    }

                val request = call.receive<DocumentDto.CreateDocumentLinkRequestDto>()
                val response = documentManagementService.createDocumentLinks(request, linkedByUuid)
                call.respond(HttpStatusCode.Created, response)
            }

            /**
             * List a document's linked documents.
             *
             * Description: Everything linked to this document, in either direction.
             *
             * Tag: documents
             * Path: id Document identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No document with that id.
             */
            get("/{id}/linked") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid document ID",
                        )
                val response = documentManagementService.getLinkedDocuments(id)
                call.respond(HttpStatusCode.OK, response)
            }

            /**
             * Remove a document link.
             *
             * Description: Requires MANAGER role. Deletes the link only — both documents are left untouched.
             *
             * Tag: documents
             * Path: linkId Document link identifier.
             * Response: 400 linkId is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No document link with that id.
             */
            delete("/link/{linkId}") {
                val linkId =
                    call.parameters["linkId"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid link ID",
                        )
                documentManagementService.deleteDocumentLink(linkId)
                call.respond(
                    HttpStatusCode.OK,
                    mapOf("message" to "Document link deleted successfully"),
                )
            }

            /**
             * List unlinked documents.
             *
             * Description: The working list for chasing paperwork that has not been matched to anything yet. Optional query parameters narrow it — `fileType`, a comma-separated `excludeTypes`, a free-text `search`, and `startDate`/`endDate`. Pages with `offset` (default 0) and `limit` (default 50).
             *
             * Tag: documents
             * Response: 401 Missing or invalid bearer token.
             */
            get("/unlinked") {
                val fileType = call.request.queryParameters["fileType"]
                val excludeTypes =
                    call.request.queryParameters["excludeTypes"]
                        ?.split(",")
                        ?.map { it.trim() }
                        ?.filter { it.isNotBlank() } ?: emptyList()
                val search = call.request.queryParameters["search"]
                val startDate = call.request.queryParameters["startDate"]
                val endDate = call.request.queryParameters["endDate"]
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50

                val response =
                    documentManagementService.getUnlinkedDocuments(
                        fileType,
                        offset,
                        limit,
                        search,
                        excludeTypes,
                        startDate,
                        endDate,
                    )
                call.respond(HttpStatusCode.OK, response)
            }

            // ===== TYPE-SPECIFIC METADATA UPDATE ENDPOINTS =====

            /**
             * Update a document's bank statement metadata.
             *
             * Description: Requires MANAGER role. Corrects the extracted statement fields — bank, IBAN, statement number, period and opening and closing balances. Money fields travel as decimal strings.
             *
             * Tag: documents
             * Path: id Document identifier.
             * Response: 400 id is not a number, or the body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No document with that id.
             */
            put("/{id}/bank-statement-metadata") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid document ID",
                        )

                val request = call.receive<DocumentDto.UpdateBankStatementMetadataRequestDto>()
                val response = documentManagementService.updateBankStatementMetadata(id, request)
                call.respond(HttpStatusCode.OK, response)
            }

            /**
             * Read a document's normalized invoice aggregate.
             *
             * Description: Returns the earliest invoice when a document carries several — source_document_id is deliberately non-unique, since one scanned PDF may hold more than one invoice, and a second invoice is addressable only by its own id through /api/invoices/{id}. A document with no invoice at all is the normal state of a freshly scanned PDF.
             *
             * Tag: documents
             * Path: id Document identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 The document has no invoice.
             */
            get("/{id}/invoice") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid document ID",
                        )
                val response = documentManagementService.getInvoiceForDocument(id)
                call.respond(HttpStatusCode.OK, response)
            }

            /**
             * Create a hand-entered invoice against a document.
             *
             * Description: Requires MANAGER role. The server stamps extraction_source = MANUAL. The document may already carry an invoice — source_document_id is non-unique by design — so this creates a second one rather than failing, and the calling UI is responsible for not offering the action twice.
             *
             * Tag: documents
             * Path: id Document identifier.
             * Response: 400 id is not a number, or the body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No document with that id.
             */
            post("/{id}/invoice") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid document ID",
                        )

                val request = call.receive<DocumentDto.InvoiceContentRequestDto>()
                val response = documentManagementService.createInvoiceForDocument(id, request)
                call.respond(HttpStatusCode.Created, response)
            }

            /**
             * Move a document through the operator workflow.
             *
             * Description: Requires MANAGER role. Only IN_PROGRESS and REVIEWED are accepted — the parser's outcomes and the vestigial queue states are rejected with 400 by the service.
             *
             * Tag: documents
             * Path: id Document identifier.
             * Response: 400 id is not a number, or the target status is not allowed.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No document with that id.
             */
            put("/{id}/status") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid document ID",
                        )

                val request = call.receive<DocumentDto.UpdateDocumentStatusRequestDto>()
                val response = documentManagementService.updateDocumentStatus(id, request.status)
                call.respond(HttpStatusCode.OK, response)
            }

            // ===== DATEV EINGANGSRECHNUNGEN (AP) EXPORT =====

            /**
             * Export incoming invoices as DATEV AP.
             *
             * Description: Builds the Eingangsrechnungen (accounts-payable) export for the accountant. `startDate` and `endDate` are required; `periodLabel` names the period in the archive. `excludeKassenbuchLinked` defaults to true, so invoices already booked in the cash book are not sent twice. The export is recorded in the DATEV export log.
             *
             * Tag: documents
             * Response: 400 startDate or endDate is missing, or VALIDATION_FAILED — one of them is not a `yyyy-MM-dd` date, or the DATEV export path is not configured or not a writable directory (bad configuration, so a 400 — not a 503).
             * Response: 401 Missing or invalid bearer token.
             */
            get("/export/datev-ap") {
                val startDate =
                    call.request.queryParameters.requireIsoDate("startDate")
                val endDate =
                    call.request.queryParameters.requireIsoDate("endDate")
                val periodLabel = call.request.queryParameters["periodLabel"] ?: ""
                val exportedByUserId = UUID.fromString(call.getEmployeeIdFromJwt())

                val excludeKassenbuchLinked =
                    call.request.queryParameters["excludeKassenbuchLinked"]
                        ?.toBooleanStrictOrNull() ?: true

                call.respond(
                    HttpStatusCode.OK,
                    datevApExportService.export(
                        startDate,
                        endDate,
                        exportedByUserId,
                        periodLabel,
                        DatevExportSource.AUTO,
                        excludeKassenbuchLinked,
                    ),
                )
            }

            /**
             * Preview the DATEV AP export.
             *
             * Description: Counts and totals for the period without building the archive. It honours the same `excludeKassenbuchLinked` default as the export, so the preview matches what would actually be produced.
             *
             * Tag: documents
             * Response: 400 startDate or endDate is missing.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/export/datev-ap/statistics") {
                val startDate =
                    call.request.queryParameters["startDate"]
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "startDate parameter required",
                        )
                val endDate =
                    call.request.queryParameters["endDate"]
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "endDate parameter required",
                        )

                val excludeKassenbuchLinked =
                    call.request.queryParameters["excludeKassenbuchLinked"]
                        ?.toBooleanStrictOrNull() ?: true

                call.respond(
                    HttpStatusCode.OK,
                    datevApExportService.getApExportStatistics(startDate, endDate, excludeKassenbuchLinked),
                )
            }
        }
    }
}

/**
 * Turns the `processingStatus` query string into the enum list, or rejects the request.
 *
 * The parameter is comma-separated and singular by name: `?processingStatus=NONE,UNPROCESSABLE,FAILED`
 * is adminApp's "Zu erfassen" bucket. It stays singular because that is the shipped wire name; the
 * Kotlin side is plural because it is a list.
 *
 * Every token is checked. Deliberately not `valueOf`-with-a-null-fallback and not a filtering
 * `mapNotNull`: silently dropping an unparseable token would *widen* the bucket a client asked for —
 * in the worst case to the whole archive — and the response would look entirely normal. A typo has
 * to be loud. The message names the accepted values so a caller can fix it without reading the source.
 *
 * A blank or empty parameter returns null, i.e. no filter. "Match nothing" is not a reachable state
 * by design — see DocumentRepositoryImpl.buildSearchQuery's predicate.
 */
private fun parseProcessingStatuses(raw: String): List<ProcessingStatus>? {
    val tokens = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return null
    return tokens
        .map { token ->
            ProcessingStatus.entries.firstOrNull { it.name == token }
                ?: throw PosException(
                    PosErrorCode.INVALID_REQUEST,
                    HttpStatusCode.BadRequest,
                    "Unknown processingStatus '$token'; expected one of " +
                        ProcessingStatus.entries.joinToString(", ") { it.name },
                )
        }.distinct()
}
