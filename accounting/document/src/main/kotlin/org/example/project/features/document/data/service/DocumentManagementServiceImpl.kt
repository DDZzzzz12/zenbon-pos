/**
 * Document Management Service Implementation
 *
 * Handles CRUD operations, file uploads, search, and file serving for documents.
 * Supports multi-format files: PDF, JPEG, PNG.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, Error> in Stage B Batch 6.
 *
 * CRITICAL ARCHITECTURE PATTERN:
 * - Service controls transaction boundaries using dbQuery
 * - Repository does NOT use dbQuery wrapper
 * - Returns DTOs (not entities)
 * - Coordinates between repository and file system
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.document.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.example.project.banking.BankStatementParseResult
import org.example.project.banking.BankStatementParser
import org.example.project.banking.model.BankStatement
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.DocumentType
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.core.domain.enums.document.StatementImportNotice
import org.example.project.core.error.PosErrorCode
import org.example.project.einvoice.EInvoiceParseResult
import org.example.project.einvoice.EInvoiceParser
import org.example.project.einvoice.model.EInvoice
import org.example.project.features.bank.data.repository.BankAccountRepositoryImpl
import org.example.project.features.bank.data.service.StatementAccountResolver
import org.example.project.features.document.data.pdf.PdfUnlockResult
import org.example.project.features.document.data.pdf.PdfUnlocker
import org.example.project.features.document.data.repository.InvoiceStorno
import org.example.project.features.document.database.tables.BankStatementTable
import org.example.project.features.document.domain.mapper.toDto
import org.example.project.features.document.domain.mapper.toListItemDto
import org.example.project.features.document.domain.mapper.toMetadataDto
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.document.domain.repository.DocumentRepository
import org.example.project.features.document.domain.service.DocumentKassenbuchLinkResolver
import org.example.project.features.document.domain.service.DocumentManagementService
import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.features.supplier.data.service.SupplierResolver
import org.example.project.infrastructure.errors.PosException
import org.example.project.pdf.util.FileStorageHelper
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.Json as KotlinxJson

/**
 * Implementation of document management service.
 */
@OptIn(ExperimentalTime::class)
class DocumentManagementServiceImpl(
    private val repository: DocumentRepository,
    private val configManager: IShopConfigurationManager,
    private val kassenbuchLinks: DocumentKassenbuchLinkResolver,
    private val eInvoiceParser: EInvoiceParser,
    private val bankStatementParser: BankStatementParser,
) : DocumentManagementService {
    private val logger = LoggerFactory.getLogger(DocumentManagementServiceImpl::class.java)

    /** Persists what [eInvoiceParser] returns. Stateless, so a field rather than a constructor arg. */
    private val parsedInvoiceWriter = ParsedInvoiceWriter(repository)

    /** The [bankStatementParser]'s counterpart, stateless for the same reason. */
    private val parsedStatementWriter = ParsedStatementWriter(repository)

    /**
     * Assigns a freshly imported statement to the registered own account its IBAN names (T9-17) —
     * the same matching the startup sweep and "Zuordnung erneut prüfen" use. Constructed like
     * [supplierResolver] below.
     */
    private val statementAccountResolver = StatementAccountResolver(BankAccountRepositoryImpl())

    /**
     * Creditor resolution for hand-keyed invoices, so they reach the same supplier link the parse
     * path gives structured ones. Same construction as [ParsedInvoiceWriter]'s own default.
     */
    private val supplierResolver = SupplierResolver(SupplierRepositoryImpl())

    companion object {
        const val MAXUPLOADMB = 20

        /** Provenance for an invoice a human typed. The parser's counterpart is `EINVOICE_XML`. */
        private const val MANUAL_EXTRACTION_SOURCE = "MANUAL"

        /**
         * The workflow states an operator may write. The parser's outcomes (`COMPLETED`, `FAILED`,
         * `UNPROCESSABLE`, `NONE`) and the vestigial queue states are not the operator's to set.
         */
        private val OPERATOR_SETTABLE_STATUSES =
            setOf(ProcessingStatus.IN_PROGRESS, ProcessingStatus.REVIEWED)
    }

    /**
     * Runs [block] on the bytes that should be archived: the upload itself, or — when an unlock was
     * requested and succeeds — a temporary unlocked copy, deleted afterwards.
     *
     * An unlock that fails for a reason other than the password (not actually encrypted, corrupt)
     * falls through to the untouched file: refusing to archive a Beleg over a convenience would be
     * worse than archiving it locked, which is today's behaviour. An oversized file skips the unlock
     * (it is not read into memory) and is refused by the size check in [storeUpload].
     */
    private suspend fun <T> withUnlockedCopy(
        file: File,
        unlockPassword: String?,
        block: suspend (File) -> T,
    ): T {
        if (unlockPassword == null) return block(file)
        if (!FileStorageHelper.isFileSizeValid(file.length(), MAXUPLOADMB)) return block(file)
        val bytes =
            try {
                file.readBytes()
            } catch (e: IOException) {
                throw fileReadFailed(e.message ?: "I/O failure", cause = e)
            }
        if (detectMediaType(bytes) != "application/pdf") return block(file)
        return when (val result = PdfUnlocker.unlock(bytes, unlockPassword)) {
            is PdfUnlockResult.Unlocked -> {
                val unlocked = File.createTempFile("unlocked_", ".tmp")
                try {
                    unlocked.writeBytes(result.bytes)
                    logger.info("uploadDocument: unlocked a password-protected PDF before storing it")
                    block(unlocked)
                } finally {
                    unlocked.delete()
                }
            }
            PdfUnlockResult.NotEncrypted -> block(file)
            PdfUnlockResult.PasswordRequired -> throw pdfPasswordRequired()
            PdfUnlockResult.PasswordIncorrect -> throw pdfPasswordIncorrect()
            is PdfUnlockResult.Unreadable -> {
                logger.warn("uploadDocument: unlock requested but the PDF was not unlockable ({}); storing as sent", result.reason)
                block(file)
            }
        }
    }

    /**
     * Detects the media type of a file from its magic bytes.
     *
     * The client-declared `Content-Type` is deliberately never consulted. Browsers send
     * `application/octet-stream` for any extension they do not recognise, and this value decides
     * whether [eInvoiceParser] looks inside the file at all — trusting the client would file a real
     * e-invoice as a plain scan, silently and with no error anywhere.
     */
    private fun detectMediaType(bytes: ByteArray): String {
        if (bytes.size < 4) return "application/octet-stream"
        return when {
            bytes[0] == 0x25.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x44.toByte() &&
                bytes[3] == 0x46.toByte() -> "application/pdf"
            bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte() -> "image/jpeg"
            bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte() -> "image/png"
            // XML first: an XML file whose text happens to contain a tag-shaped token must not be
            // claimed as MT940.
            FileStorageHelper.startsWithXmlDeclaration(bytes) -> "application/xml"
            FileStorageHelper.looksLikeMt940(bytes) -> FileStorageHelper.MEDIA_TYPE_MT940
            else -> "application/octet-stream"
        }
    }

    override suspend fun uploadDocument(
        file: File,
        request: DocumentDto.UploadDocumentRequestDto,
        uploadedBy: UUID,
        unlockPassword: String?,
    ): DocumentDto.DocumentResponseDto =
        ExceptionConverter.catchingThrows(
            context = "uploadDocument(uploadedBy=$uploadedBy)",
            employeeId = uploadedBy.toString(),
            operation = "UPLOAD_DOCUMENT",
            entityType = "Document",
        ) {
            withUnlockedCopy(file, unlockPassword) { stored -> storeUpload(stored, request, uploadedBy) }
        }

    /**
     * Everything an upload does once the bytes to archive are settled: validate, de-duplicate, store,
     * parse. It sees only the file it is handed, so an unlocked copy and an untouched upload are
     * indistinguishable below this line — the hash, media type, stored size and e-invoice parser all
     * describe the bytes that are actually archived.
     */
    private suspend fun storeUpload(
        file: File,
        request: DocumentDto.UploadDocumentRequestDto,
        uploadedBy: UUID,
    ): DocumentDto.DocumentResponseDto {
        try {
            request.validate()
        } catch (e: IllegalArgumentException) {
            throw validationFailed(e.message ?: "Invalid upload request")
        }

        val documentStoragePath = configManager.getDocumentStoragePath()

        // Validates file (throws PosException on failure)
        validateFile(file, MAXUPLOADMB)

        val contentHash = FileStorageHelper.calculateFileHash(file)

        // Detect media type from file content
        val fileBytes =
            try {
                file.readBytes()
            } catch (e: IOException) {
                throw fileReadFailed(e.message ?: "I/O failure", cause = e)
            }
        val mediaType = detectMediaType(fileBytes)

        // Check for duplicates
        val existingDoc = dbQuery { repository.findByContentHash(contentHash) }
        if (existingDoc != null) {
            throw duplicateContent()
        }

        val now = currentTimeStamp()

        // Parse file type - support both enum types and additional configured types
        val fileTypeEnum = DocumentType.fromStringOrNull(request.fileType)
        val isAdditionalType = fileTypeEnum == null
        if (isAdditionalType) {
            val additionalTypes = configManager.getAdditionalDocumentTypes()
            if (request.fileType !in additionalTypes) {
                throw validationFailed("Invalid fileType: ${request.fileType}")
            }
        }
        val effectiveTypeName = request.fileType

        // Generate filename with OCR metadata for better naming
        val filename =
            if (isAdditionalType) {
                FileStorageHelper.generateFilename(effectiveTypeName, now, request.description)
            } else {
                FileStorageHelper.generateFilename(
                    type = fileTypeEnum!!,
                    timestamp = now,
                    contentHash = contentHash,
                    vendorName = request.vendorName,
                    invoiceNumber = request.invoiceNumber,
                    bankName = request.bankName,
                    statementNumber = request.statementNumber,
                    identifier = request.description,
                    // The type detected from the magic bytes, so an XRechnung is filed as .xml
                    // rather than under a .pdf name no reader can open (E2E-2026-09-20 #7).
                    mediaType = mediaType,
                )
            }

        // Generate relative path for DB storage
        val relativePath =
            if (isAdditionalType) {
                FileStorageHelper.generateRelativePath(effectiveTypeName, now, filename)
            } else {
                FileStorageHelper.generateRelativePath(fileTypeEnum!!, now, filename)
            }

        // Resolve to absolute path for file operations
        val filePath = "$documentStoragePath/$relativePath"

        // Ensure directory exists & write file
        try {
            if (isAdditionalType) {
                FileStorageHelper.ensureMonthDirectoryExists(documentStoragePath, effectiveTypeName, now)
            } else {
                FileStorageHelper.ensureMonthDirectoryExists(documentStoragePath, fileTypeEnum!!, now)
            }
        } catch (e: IOException) {
            throw fileWriteFailed(e.message ?: "I/O failure creating directory", cause = e)
        }

        if (!FileStorageHelper.isPathSafe(filePath, documentStoragePath)) {
            throw validationFailed("Invalid file path")
        }

        // Copy file to storage
        try {
            val destFile = File(filePath)
            file.copyTo(destFile, overwrite = false)
        } catch (e: IOException) {
            throw fileWriteFailed(e.message ?: "I/O failure copying file", cause = e)
        }

        // Build metadata JSON for additional types
        val effectiveMetadata =
            if (isAdditionalType) {
                // Serialized, never interpolated. The type name is allow-listed against
                // getAdditionalDocumentTypes(), but that list is operator free text whose validation
                // rejects blanks, length and path separators — not quotes. Matches how the merge
                // branch below already writes the same key.
                val additionalMeta =
                    KotlinxJson.encodeToString(
                        JsonObject.serializer(),
                        JsonObject(mapOf("additionalTypeName" to JsonPrimitive(effectiveTypeName))),
                    )
                val requestMetadata = request.metadata
                if (requestMetadata != null) {
                    try {
                        val existing =
                            kotlinx.serialization.json.Json
                                .parseToJsonElement(requestMetadata)
                                .jsonObject
                        val merged =
                            buildMap {
                                putAll(existing)
                                put("additionalTypeName", JsonPrimitive(effectiveTypeName))
                            }
                        kotlinx.serialization.json.Json
                            .encodeToString(JsonObject.serializer(), JsonObject(merged))
                    } catch (_: Exception) {
                        additionalMeta
                    }
                } else {
                    additionalMeta
                }
            } else {
                request.metadata
            }

        // A client built before Stage 4 still POSTs these. They are ignored rather than
        // rejected — a 400 would stop an un-upgraded POS filing a receipt at all, which is a
        // worse outcome than losing metadata that now has a replacement entry path in adminApp's
        // capture form. Logged so an old client in the field leaves a trace rather than only a
        // gap in the data.
        if (effectiveTypeName == "INVOICE") {
            val ignored =
                listOfNotNull(
                    request.vendorName?.let { "vendorName" },
                    request.invoiceNumber?.let { "invoiceNumber" },
                    request.invoiceDate?.let { "invoiceDate" },
                    request.dueDate?.let { "dueDate" },
                    request.netAmount?.let { "netAmount" },
                    request.grossAmount?.let { "grossAmount" },
                    request.taxAmount?.let { "taxAmount" },
                    request.taxRate?.let { "taxRate" },
                    request.paymentReference?.let { "paymentReference" },
                    request.taxLineItems?.takeIf { it.isNotEmpty() }?.let { "taxLineItems" },
                )
            if (ignored.isNotEmpty()) {
                logger.warn(
                    "Upload of {} carried deprecated invoice fields that are no longer stored: {}. " +
                        "Enter invoice data through the adminApp capture form instead.",
                    filename,
                    ignored.joinToString(", "),
                )
            }
        }

        // Create database record and type-specific metadata
        val dto =
            dbQuery {
                val document =
                    repository.create(
                        filename = filename,
                        fileType = effectiveTypeName,
                        filePath = relativePath,
                        fileSize = file.length(),
                        uploadedAt = now,
                        uploadedBy = uploadedBy,
                        contentHash = contentHash,
                        metadataJson = effectiveMetadata,
                        ocrText = request.ocrText,
                        mediaType = mediaType,
                    )

                // Only bank statements still get type-specific metadata written at upload time.
                //
                // The "INVOICE" arm was removed in Stage 4: an invoice's data now comes either
                // from the supplier's own e-invoice payload (parseEInvoice, below, writing the
                // normalized model under EINVOICE_XML) or from an operator typing it into
                // adminApp's capture form afterwards. Nothing creates a legacy
                // document_invoice_metadata row any more.
                //
                // Bank statements are deliberately untouched. They have no normalized write path
                // at all — no parser, no capture form — so removing this arm would delete the
                // only way bank-statement metadata is ever recorded. The e-invoice programme is
                // scoped to invoices; CAMT/MT940 belong to the :accounting-banking module.
                when (effectiveTypeName) {
                    "BANK_STATEMENT" -> {
                        val hasBankData =
                            request.bankName != null ||
                                request.statementNumber != null ||
                                request.statementDate != null ||
                                request.iban != null ||
                                request.closingBalance != null
                        if (hasBankData) {
                            repository.createBankStatementMetadata(
                                documentId = document.id.value,
                                bankName = request.bankName,
                                bic = request.bic,
                                iban = request.iban,
                                accountNumber = request.accountNumber,
                                statementNumber = request.statementNumber,
                                statementDate = request.statementDate?.let { parseDate(it) },
                                periodStart = request.periodStart?.let { parseDate(it) },
                                periodEnd = request.periodEnd?.let { parseDate(it) },
                                openingBalance = request.openingBalance?.toBigDecimalOrNull(),
                                closingBalance = request.closingBalance?.toBigDecimalOrNull(),
                                currency = request.currency,
                            )
                        }
                    }
                    else -> { /* INVOICE/CONTRACTS/CUSTOM write no type-specific metadata here */ }
                }

                toDto(
                    document,
                    kassenbuchLinks,
                    repository.getInvoiceBySourceDocument(document.id.value),
                    repository.getBankStatementBySourceDocument(document.id.value),
                )
            }

        parseEInvoice(fileBytes, mediaType, documentId = dto.id)
        val statementNotices =
            parseBankStatement(
                fileBytes,
                mediaType,
                documentId = dto.id,
                declaredAsStatement = effectiveTypeName == DocumentType.BANK_STATEMENT.name,
            )

        // Re-read, because `dto` was built BEFORE the parsers ran and therefore still says
        // processingStatus=NONE with no invoice attached — the pre-parse state of a document
        // that may well have been fully extracted a line ago. Returning that snapshot told every
        // uploader "no e-invoice found", always, including for a file the server had just read
        // perfectly: the outcome was only visible to someone who fetched the document again.
        //
        // The parsers commit in their own transactions, so this reads what they actually wrote.
        // It falls back to the snapshot rather than throwing if the row cannot be re-read: the
        // upload itself succeeded, and failing it here would discard a stored document over a
        // presentation detail.
        //
        // The statement notices are added afterwards because they are not stored anywhere a
        // re-read could find them: a statement that does not reconcile is refused, so there is
        // no row to flag. They exist only on this response, which is the one moment the
        // operator is looking at the import.
        return reloadAfterParse(dto).copy(statementImportNotices = statementNotices)
    }

    /** The document as it stands once the parsers have had their say. See the call site. */
    private suspend fun reloadAfterParse(uploaded: DocumentDto.DocumentResponseDto): DocumentDto.DocumentResponseDto =
        runCatching { getDocumentById(uploaded.id) }
            .getOrElse { failure ->
                logger.warn(
                    "uploadDocument: document {} stored, but re-reading it after parsing failed; " +
                        "returning the pre-parse response",
                    uploaded.id,
                    failure,
                )
                uploaded
            }

    /**
     * Extracts the structured invoice an upload may carry, and records it against [documentId].
     *
     * Structured parsing is deterministic and takes milliseconds, so it runs inline on the upload
     * rather than on a queue — it depends on nothing external being reachable. An
     * upload never fails because parsing failed: the document is already stored and committed by the
     * time this runs, and a document with no structured payload is the normal scanned-invoice case.
     *
     * The `when` is exhaustive on purpose and must stay that way. A fifth [EInvoiceParseResult]
     * variant should break this build rather than be silently ignored here.
     *
     * Each branch records its outcome on the document via [recordProcessingStatus], so an operator
     * can tell a document that was read automatically from one that still needs typing in.
     * `NoEInvoiceFound` writes nothing: `NONE` is the column default and the normal scan case.
     */
    private suspend fun parseEInvoice(
        fileBytes: ByteArray,
        mediaType: String,
        documentId: Long,
    ) {
        // parse() converts every outcome — including a hostile file — into a value, so this call
        // needs no guard of its own.
        when (val parsed = eInvoiceParser.parse(fileBytes, mediaType)) {
            is EInvoiceParseResult.Success -> {
                val stored = writeParsedInvoice(parsed.invoice, documentId)
                // Only claim COMPLETED once the invoice is actually behind it.
                if (stored) {
                    recordProcessingStatus(documentId, ProcessingStatus.COMPLETED)
                } else {
                    recordProcessingStatus(documentId, ProcessingStatus.FAILED)
                }
            }

            is EInvoiceParseResult.NotConformant -> {
                logger.info(
                    "uploadDocument: document {} carries profile {} which is not an EN 16931 invoice: {}",
                    documentId,
                    parsed.profile,
                    parsed.reason,
                )
                recordProcessingStatus(documentId, ProcessingStatus.UNPROCESSABLE)
            }

            // NONE is the column default and the normal case for a scan, so nothing is written.
            EInvoiceParseResult.NoEInvoiceFound ->
                logger.debug("uploadDocument: document {} carries no structured payload", documentId)

            is EInvoiceParseResult.Failure -> {
                logger.warn(
                    "uploadDocument: document {} has a structured payload that could not be parsed: {}",
                    documentId,
                    parsed.reason,
                )
                recordProcessingStatus(documentId, ProcessingStatus.FAILED)
            }
        }
    }

    /**
     * Extracts the bank statement an upload may carry, and records it against [documentId].
     *
     * Runs alongside [parseEInvoice] over the same bytes and is **not** gated on the client-declared
     * `fileType`: the client's declaration decides nothing about what we look for inside a file, the
     * same rule that already applies to `Content-Type`. The two parsers' format detectors are
     * mutually exclusive, so at most one of them ever records a status for a given document — that
     * exclusivity is what keeps `processing_status` single-writer.
     *
     * The `when` is exhaustive on purpose and must stay that way: a fourth
     * [BankStatementParseResult] variant should break this build rather than be silently ignored.
     *
     * An upload never fails because parsing failed — the document is already stored and committed by
     * the time this runs, and a file carrying no statement is the normal case.
     *
     * Returns what the operator must be told about the import beyond its [ProcessingStatus]: that
     * the balances did not reconcile (the statement was refused), or that the stored statement
     * overlaps one already imported. Both used to reach only the server log.
     *
     * [declaredAsStatement] — the client filed the upload as a bank statement — decides nothing about
     * parsing. It only decides whether "no statement in here" is news: for a file the operator picked
     * in "Auszug importieren" it is ([StatementImportNotice.NOT_A_BANK_STATEMENT], E2E 18.8), for a
     * receipt filed in Belege it is the normal case and nothing is said.
     */
    private suspend fun parseBankStatement(
        fileBytes: ByteArray,
        mediaType: String,
        documentId: Long,
        declaredAsStatement: Boolean,
    ): List<StatementImportNotice> {
        // parse() converts every outcome — including a hostile file — into a value, so this call
        // needs no guard of its own.
        return when (val parsed = bankStatementParser.parse(fileBytes, mediaType)) {
            is BankStatementParseResult.Success -> {
                val statementId = writeParsedStatement(parsed.statement, documentId)
                // Only claim COMPLETED once the statement is actually behind it.
                if (statementId != null) {
                    resolveStatementAccount(statementId)
                    recordProcessingStatus(documentId, ProcessingStatus.COMPLETED)
                    listOfNotNull(overlapNotice(statementId))
                } else {
                    recordProcessingStatus(documentId, ProcessingStatus.FAILED)
                    emptyList()
                }
            }

            // NONE is the column default and the normal case for an invoice or a scan.
            BankStatementParseResult.NoStatementFound -> {
                logger.debug("uploadDocument: document {} carries no bank statement", documentId)
                if (declaredAsStatement) listOf(StatementImportNotice.NOT_A_BANK_STATEMENT) else emptyList()
            }

            is BankStatementParseResult.Failure -> {
                logger.warn(
                    "uploadDocument: document {} looks like a bank statement but could not be read: {}",
                    documentId,
                    parsed.reason,
                )
                recordProcessingStatus(documentId, ProcessingStatus.FAILED)
                if (parsed.balancesDoNotReconcile) listOf(StatementImportNotice.BALANCES_DO_NOT_RECONCILE) else emptyList()
            }
        }
    }

    /**
     * Assigns the just-stored statement to our own account when its IBAN is registered (T9-17).
     *
     * In a transaction of its own, after the statement committed, and a failure is only logged: the
     * statement is stored either way, and the startup sweep or "Zuordnung erneut prüfen" still
     * assign it later. An unknown IBAN is the normal case for a foreign statement and leaves it
     * under "Auszüge ohne Konto".
     */
    private suspend fun resolveStatementAccount(statementId: Long) {
        runCatching { dbQuery { statementAccountResolver.resolve(statementId) } }
            .onFailure { logger.warn("uploadDocument: account of statement {} could not be resolved", statementId, it) }
    }

    /**
     * [StatementImportNotice.OVERLAPS_EARLIER_STATEMENT] when `StatementDuplicateMarker` recorded an
     * overlap for the statement just written, else null.
     *
     * Read in a fresh transaction, after the write committed: the marker sets the column with a DSL
     * update, which the entity cache of the writing transaction would not reflect. A failed read
     * costs the hint and nothing else — the statement is stored and its duplicates are marked.
     */
    private suspend fun overlapNotice(statementId: Long): StatementImportNotice? =
        runCatching {
            dbQuery {
                repository
                    .getBankStatement(statementId)
                    ?.readValues
                    ?.get(BankStatementTable.overlappingStatementId)
            }
        }.onFailure { logger.warn("uploadDocument: overlap of statement {} could not be read", statementId, it) }
            .getOrNull()
            ?.let { StatementImportNotice.OVERLAPS_EARLIER_STATEMENT }

    /**
     * Writes the parsed statement in a transaction of its own, and swallows a failure.
     *
     * Both halves matter, for the same reasons as [writeParsedInvoice]: a `try`/`catch` inside the
     * upload's own transaction would not be enough, because on PostgreSQL a failed statement aborts
     * the surrounding transaction and the subsequent COMMIT would still roll the document back — the
     * upload would report success while storing nothing. A separate transaction confines the damage
     * to the statement rows, which is the only correct blast radius: the statement is derived data,
     * and the operator's document must survive its loss.
     *
     * Returns the stored statement's id, or null when nothing was stored, so the caller does not
     * record `COMPLETED` with nothing behind it.
     */
    private suspend fun writeParsedStatement(
        statement: BankStatement,
        documentId: Long,
    ): Long? =
        try {
            dbQuery { parsedStatementWriter.write(statement, sourceDocumentId = documentId) }
        } catch (e: Exception) {
            logger.error(
                "uploadDocument: document {} parsed as a bank statement but it could not be stored: {}",
                documentId,
                e.message,
                e,
            )
            null
        }

    /**
     * Writes the parsed invoice in a transaction of its own, and swallows a failure.
     *
     * Both halves of that matter. A `try`/`catch` inside the upload's own transaction would not be
     * enough: on PostgreSQL a failed statement aborts the surrounding transaction, so the catch
     * would run but the subsequent COMMIT would still roll the document back — the upload would
     * report success while storing nothing. A separate transaction confines the damage to the
     * invoice rows, which is the only correct blast radius: the invoice is derived data, and the
     * operator's document must survive its loss.
     *
     * Returns whether the invoice was stored, so the caller does not record a `COMPLETED` status
     * with no invoice behind it.
     */
    private suspend fun writeParsedInvoice(
        invoice: EInvoice,
        documentId: Long,
    ): Boolean =
        try {
            dbQuery { parsedInvoiceWriter.write(invoice, sourceDocumentId = documentId) }
            true
        } catch (e: Exception) {
            logger.error(
                "uploadDocument: document {} parsed as an e-invoice but the invoice could not be stored: {}",
                documentId,
                e.message,
                e,
            )
            false
        }

    /**
     * Records the parse outcome against the document, in its own transaction, swallowing failure.
     *
     * Same discipline and same reason as [writeParsedInvoice]: this runs after the upload's
     * transaction has already committed, and the operator's document must survive the loss of a
     * status write. A status is a hint about derived data — it is never worth failing an upload.
     */
    private suspend fun recordProcessingStatus(
        documentId: Long,
        status: ProcessingStatus,
    ) {
        try {
            dbQuery { repository.updateProcessingStatus(documentId, status) }
        } catch (e: Exception) {
            logger.error(
                "uploadDocument: document {} could not record processing status {}: {}",
                documentId,
                status,
                e.message,
                e,
            )
        }
    }

    /**
     * Parses a date string (ISO 8601 date or datetime) to LocalDateTime.
     */
    private fun parseDate(dateStr: String): LocalDateTime =
        try {
            LocalDateTime.parse(dateStr)
        } catch (_: Exception) {
            val date = LocalDate.parse(dateStr)
            LocalDateTime(date.year, date.month, date.day, 0, 0, 0)
        }

    override suspend fun getDocumentById(id: Long): DocumentDto.DocumentResponseDto =
        dbQuery {
            val document = repository.findById(id) ?: throw documentNotFound(id)
            toDto(
                document,
                kassenbuchLinks,
                repository.getInvoiceBySourceDocument(document.id.value),
                repository.getBankStatementBySourceDocument(document.id.value),
                allocationCount = repository.getAllocationCountsByDocuments(listOf(document.id.value))[document.id.value] ?: 0,
            )
        }

    override suspend fun getDocumentMetadata(id: Long): DocumentDto.DocumentMetadataDto =
        dbQuery {
            val document = repository.findById(id) ?: throw documentNotFound(id)
            document.toMetadataDto(
                kassenbuchLinks,
                repository.getInvoiceBySourceDocument(id),
                repository.getBankStatementBySourceDocument(id),
            )
        }

    override suspend fun getDocumentFile(id: Long): File {
        val documentStoragePath = configManager.getDocumentStoragePath()
        val document =
            dbQuery {
                repository.findById(id) ?: throw documentNotFound(id)
            }

        val resolvedPath = FileStorageHelper.resolveStoredPath(document.filePath, documentStoragePath)
        val file = File(resolvedPath)

        if (!file.exists()) {
            throw documentFileMissing(id)
        }

        if (!FileStorageHelper.isPathSafe(resolvedPath, documentStoragePath)) {
            throw fileAccessDenied()
        }

        return file
    }

    override suspend fun searchDocuments(filter: DocumentDto.DocumentSearchFilterDto): DocumentDto.DocumentListResponseDto =
        dbQuery {
            try {
                filter.validate()
            } catch (e: IllegalArgumentException) {
                throw validationFailed(e.message ?: "Invalid search filter")
            }

            val fileType = filter.fileType
            val fromDate =
                filter.fromDate?.let { dateStr ->
                    try {
                        LocalDateTime.parse(dateStr)
                    } catch (_: Exception) {
                        LocalDateTime.parse("${dateStr}T00:00:00")
                    }
                }
            val toDate =
                filter.toDate?.let { dateStr ->
                    try {
                        LocalDateTime.parse(dateStr)
                    } catch (_: Exception) {
                        LocalDateTime.parse("${dateStr}T23:59:59")
                    }
                }
            val uploadedByUuid = filter.uploadedBy?.let { UUID.fromString(it) }

            val documents =
                repository.search(
                    fileType = fileType,
                    fromDate = fromDate,
                    toDate = toDate,
                    month = filter.month,
                    uploadedBy = uploadedByUuid,
                    activeOnly = filter.activeOnly,
                    searchTerm = filter.searchTerm,
                    vendorName = filter.vendorName,
                    invoiceNumber = filter.invoiceNumber,
                    minAmount = filter.minAmount?.toBigDecimalOrNull(),
                    maxAmount = filter.maxAmount?.toBigDecimalOrNull(),
                    bankName = filter.bankName,
                    statementNumber = filter.statementNumber,
                    processingStatuses = filter.processingStatuses,
                    offset = filter.offset,
                    limit = filter.limit,
                )

            val total =
                repository.count(
                    fileType = fileType,
                    fromDate = fromDate,
                    toDate = toDate,
                    month = filter.month,
                    uploadedBy = uploadedByUuid,
                    activeOnly = filter.activeOnly,
                    searchTerm = filter.searchTerm,
                    vendorName = filter.vendorName,
                    invoiceNumber = filter.invoiceNumber,
                    minAmount = filter.minAmount?.toBigDecimalOrNull(),
                    maxAmount = filter.maxAmount?.toBigDecimalOrNull(),
                    bankName = filter.bankName,
                    statementNumber = filter.statementNumber,
                    processingStatuses = filter.processingStatuses,
                )

            // One query for the page's invoices and one for its bank statements, not one per row:
            // this endpoint returns up to 100 rows and the mapper already costs several lazy loads
            // each.
            val documentIds = documents.map { it.id.value }
            val invoicesByDocument = repository.getInvoicesBySourceDocuments(documentIds)
            val statementsByDocument = repository.getBankStatementsBySourceDocuments(documentIds)
            val allocationsByDocument = repository.getAllocationCountsByDocuments(documentIds)
            val dtos =
                documents.map {
                    it.toListItemDto(
                        kassenbuchLinks,
                        invoicesByDocument[it.id.value],
                        statementsByDocument[it.id.value],
                        allocationsByDocument[it.id.value] ?: 0,
                    )
                }
            DocumentDto.DocumentListResponseDto(
                items = dtos,
                total = total,
                offset = filter.offset,
                limit = filter.limit,
                hasMore = (filter.offset + dtos.size) < total,
            )
        }

    override suspend fun deleteDocument(id: Long) =
        ExceptionConverter.catchingThrows<Unit>(
            context = "deleteDocument(id=$id)",
            operation = "DELETE_DOCUMENT",
            entityType = "Document",
        ) {
            // Get document info & references first inside transaction
            val document =
                dbQuery {
                    // Check for DocumentLink references
                    val linkedDocs = repository.getLinkedDocuments(id)
                    if (linkedDocs.isNotEmpty()) {
                        throw referencedByOther("DocumentLink")
                    }

                    // Check for Kassenbuch header references
                    if (kassenbuchLinks.countLinks(id) > 0) {
                        throw referencedByOther("KassenbuchHeader")
                    }

                    // T9-19: an invoice read from (or captured against) this Beleg is a received
                    // business record, and a Beleg that fed the books is never removed — not even
                    // once the invoice is cancelled, since the Storno keeps both the original and
                    // its correction on this very document. The operator's way out is
                    // `POST /api/invoices/{id}/cancel` (InvoiceCancellationService), so the deletion
                    // is refused while any invoice references the document.
                    if (repository.getInvoiceBySourceDocument(id) != null) {
                        throw referencedByOther("Invoice")
                    }

                    val doc = repository.findById(id) ?: throw documentNotFound(id)

                    // Soft delete in DB
                    repository.softDelete(id)
                    doc
                }

            // Move physical file to .trash (outside the DB transaction)
            val documentStoragePath = configManager.getDocumentStoragePath()
            val resolvedPath = FileStorageHelper.resolveStoredPath(document.filePath, documentStoragePath)
            try {
                FileStorageHelper.deleteFile(resolvedPath, documentStoragePath)
            } catch (e: IOException) {
                throw fileWriteFailed(e.message ?: "I/O failure moving to trash", cause = e)
            }
        }

    override suspend fun permanentlyDeleteDocument(id: Long) {
        val documentStoragePath = configManager.getDocumentStoragePath()
        val document =
            dbQuery {
                repository.findById(id) ?: throw documentNotFound(id)
            }
        val resolvedPath = FileStorageHelper.resolveStoredPath(document.filePath, documentStoragePath)
        try {
            FileStorageHelper.deleteFile(resolvedPath, documentStoragePath)
        } catch (e: IOException) {
            throw fileWriteFailed(e.message ?: "I/O failure deleting file", cause = e)
        }
        dbQuery { repository.softDelete(id) }
    }

    override suspend fun reactivateDocument(id: Long) {
        val documentStoragePath = configManager.getDocumentStoragePath()
        val document =
            dbQuery {
                repository.findById(id) ?: throw documentNotFound(id)
            }

        val resolvedPath = FileStorageHelper.resolveStoredPath(document.filePath, documentStoragePath)
        val rootFile = File(documentStoragePath).canonicalFile
        val sourceFile = File(resolvedPath).canonicalFile
        val relativePath = sourceFile.path.removePrefix(rootFile.path).removePrefix("/")
        val trashedFilePath = "$documentStoragePath/.trash/$relativePath"

        try {
            FileStorageHelper.restoreFile(trashedFilePath, documentStoragePath)
        } catch (e: IOException) {
            throw fileWriteFailed(e.message ?: "I/O failure restoring file", cause = e)
        }

        dbQuery { repository.reactivate(id) }
    }

    override suspend fun updateMetadata(
        id: Long,
        metadata: String,
    ): DocumentDto.DocumentResponseDto =
        dbQuery {
            val document = repository.updateMetadata(id, metadata) ?: throw documentNotFound(id)
            toDto(
                document,
                kassenbuchLinks,
                repository.getInvoiceBySourceDocument(document.id.value),
                repository.getBankStatementBySourceDocument(document.id.value),
            )
        }

    override suspend fun getStorageStatistics(): DocumentDto.DocumentStorageStatisticsDto =
        dbQuery {
            val stats = repository.getStorageStatistics()
            DocumentDto.DocumentStorageStatisticsDto(
                totalDocuments = stats["totalDocuments"] ?: 0L,
                invoiceCount = stats["invoiceCount"] ?: 0L,
                bankStatementCount = stats["bankStatementCount"] ?: 0L,
                contractsCount = stats["contractsCount"] ?: 0L,
                customCount = stats["customCount"] ?: 0L,
                totalStorageBytes = stats["totalStorageBytes"] ?: 0L,
                formattedTotalStorage = FileStorageHelper.formatFileSize(stats["totalStorageBytes"] ?: 0L),
            )
        }

    override suspend fun validateFile(
        file: File,
        maxSizeMb: Int,
    ) {
        if (!file.exists()) {
            throw validationFailed("File is required")
        }
        if (!FileStorageHelper.isFileSizeValid(file.length(), maxSizeMb)) {
            throw imageTooLarge(maxSizeMb)
        }
        if (!FileStorageHelper.isValidFile(file)) {
            throw imageTypeUnsupported("PDF, JPEG, or PNG with valid magic bytes")
        }
    }

    // ===== DOCUMENT LINKING OPERATIONS =====

    override suspend fun createDocumentLinks(
        request: DocumentDto.CreateDocumentLinkRequestDto,
        linkedBy: UUID,
    ): List<DocumentDto.DocumentLinkResponseDto> =
        dbQuery {
            try {
                request.validate()
            } catch (e: IllegalArgumentException) {
                throw validationFailed(e.message ?: "Invalid link request")
            }

            val currentTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            val links = mutableListOf<DocumentDto.DocumentLinkResponseDto>()

            val bankStatementId =
                request.bankStatementDocumentId
                    ?: throw validationFailed("bankStatementDocumentId is required")

            for (docId in request.documentIds) {
                val link =
                    repository.createLink(
                        documentId = docId,
                        bankStatementDocumentId = bankStatementId,
                        linkedBy = linkedBy,
                        linkedAt = currentTime,
                        notes = request.notes,
                    )

                links.add(
                    DocumentDto.DocumentLinkResponseDto(
                        id = link.id.value,
                        documentId = link.document.id.value,
                        documentFilename = link.document.filename,
                        documentType = link.document.fileType,
                        bankStatementDocumentId = link.bankStatementDocument.id.value,
                        bankStatementFilename = link.bankStatementDocument.filename,
                        linkedBy = link.linkedBy,
                        linkedAt = link.linkedAt.toString(),
                        notes = link.notes,
                    ),
                )
            }

            links
        }

    override suspend fun getLinkedDocuments(documentId: Long): List<DocumentDto.DocumentLinkResponseDto> =
        dbQuery {
            val links = repository.getLinkedDocuments(documentId)
            links.map { link ->
                DocumentDto.DocumentLinkResponseDto(
                    id = link.id.value,
                    documentId = link.document.id.value,
                    documentFilename = link.document.filename,
                    documentType = link.document.fileType,
                    bankStatementDocumentId = link.bankStatementDocument.id.value,
                    bankStatementFilename = link.bankStatementDocument.filename,
                    linkedBy = link.linkedBy,
                    linkedAt = link.linkedAt.toString(),
                    notes = link.notes,
                )
            }
        }

    override suspend fun deleteDocumentLink(linkId: Long) {
        dbQuery {
            try {
                repository.deleteLink(linkId)
            } catch (e: NoSuchElementException) {
                throw documentLinkNotFound(linkId)
            }
        }
    }

    override suspend fun getUnlinkedDocuments(
        fileType: String?,
        offset: Int,
        limit: Int,
        search: String?,
        excludeTypes: List<String>,
        startDate: String?,
        endDate: String?,
    ): List<DocumentDto.DocumentResponseDto> =
        dbQuery {
            val documents =
                repository.getUnlinkedDocuments(
                    fileType = fileType,
                    activeOnly = true,
                    offset = offset,
                    limit = limit,
                    search = search,
                    excludeTypes = excludeTypes,
                    startDate = startDate,
                    endDate = endDate,
                )
            // One query for the page's invoices and one for its statements, not one per row — same
            // reason searchDocuments does it.
            val documentIds = documents.map { it.id.value }
            val invoicesByDocument = repository.getInvoicesBySourceDocuments(documentIds)
            val statementsByDocument = repository.getBankStatementsBySourceDocuments(documentIds)
            documents.map {
                toDto(
                    it,
                    kassenbuchLinks,
                    invoicesByDocument[it.id.value],
                    statementsByDocument[it.id.value],
                )
            }
        }

    // ===== TYPE-SPECIFIC METADATA OPERATIONS =====

    override suspend fun updateBankStatementMetadata(
        id: Long,
        request: DocumentDto.UpdateBankStatementMetadataRequestDto,
    ): DocumentDto.DocumentResponseDto =
        dbQuery {
            try {
                request.validate()
            } catch (e: IllegalArgumentException) {
                throw validationFailed(e.message ?: "Invalid bank statement metadata")
            }

            repository.updateBankStatementMetadata(
                documentId = id,
                bankName = request.bankName,
                bic = request.bic,
                iban = request.iban,
                accountNumber = request.accountNumber,
                statementNumber = request.statementNumber,
                statementDate = request.statementDate?.let { parseDate(it) },
                periodStart = request.periodStart?.let { parseDate(it) },
                periodEnd = request.periodEnd?.let { parseDate(it) },
                openingBalance = request.openingBalance?.toBigDecimalOrNull(),
                closingBalance = request.closingBalance?.toBigDecimalOrNull(),
                currency = request.currency,
            )

            val document = repository.findById(id) ?: throw documentNotFound(id)
            toDto(
                document,
                kassenbuchLinks,
                repository.getInvoiceBySourceDocument(document.id.value),
                repository.getBankStatementBySourceDocument(document.id.value),
            )
        }

    // ===== NORMALIZED INVOICE / BANK STATEMENT AGGREGATES =====

    override suspend fun getInvoice(id: Long): DocumentDto.InvoiceDto =
        dbQuery {
            val invoice = repository.getInvoice(id) ?: throw invoiceNotFound(id)
            invoice.toDto()
        }

    /**
     * By-document read. Returns the **earliest** invoice when a document carries several — see the
     * interface KDoc and `DocumentRepository.getInvoiceBySourceDocument`.
     */
    override suspend fun getInvoiceForDocument(documentId: Long): DocumentDto.InvoiceDto =
        dbQuery {
            repository.findById(documentId) ?: throw documentNotFound(documentId)
            val invoice =
                repository.getInvoiceBySourceDocument(documentId)
                    ?: throw invoiceNotFoundForDocument(documentId)
            invoice.toDto()
        }

    override suspend fun createInvoiceForDocument(
        documentId: Long,
        request: DocumentDto.InvoiceContentRequestDto,
    ): DocumentDto.InvoiceDto =
        dbQuery {
            validated { request.validate() }
            repository.findById(documentId) ?: throw documentNotFound(documentId)

            val invoice =
                repository.createInvoice(
                    sourceDocumentId = documentId,
                    // Provenance is ours to stamp, never the request's.
                    extractionSource = MANUAL_EXTRACTION_SOURCE,
                    invoiceNumber = request.invoiceNumber,
                    issueDate = request.issueDate?.let { parseDate(it) },
                    dueDate = request.dueDate?.let { parseDate(it) },
                    deliveryDate = request.deliveryDate?.let { parseDate(it) },
                    currency = request.currency,
                    invoiceTypeCode = request.invoiceTypeCode,
                    precedingInvoiceReference = request.precedingInvoiceReference,
                    buyerReference = request.buyerReference,
                    sellerName = request.sellerName,
                    sellerVatId = request.sellerVatId,
                    sellerTaxNumber = request.sellerTaxNumber,
                    sellerStreet = request.sellerStreet,
                    sellerZip = request.sellerZip,
                    sellerCity = request.sellerCity,
                    sellerCountry = request.sellerCountry,
                    sellerPhone = request.sellerPhone,
                    sellerEmail = request.sellerEmail,
                    paymentIban = request.paymentIban,
                    paymentReference = request.paymentReference,
                    paymentTermsText = request.paymentTermsText,
                    lineNetTotal = request.lineNetTotal?.toBigDecimalOrNull(),
                    taxExclusiveTotal = request.taxExclusiveTotal?.toBigDecimalOrNull(),
                    taxTotal = request.taxTotal?.toBigDecimalOrNull(),
                    taxInclusiveTotal = request.taxInclusiveTotal?.toBigDecimalOrNull(),
                    amountDue = request.amountDue?.toBigDecimalOrNull(),
                )
            request.vatBreakdown?.let { repository.replaceVatBreakdown(invoice.id.value, it.toVatRows()) }

            // Hand-keyed invoices used to get no supplier at all — only ParsedInvoiceWriter resolved
            // one. That left every scanned invoice out of the creditor's open balance and YTD spend,
            // and out of the three match signals keyed on supplier_id (IBAN -> single open invoice,
            // fuzzy name, subset-sum), i.e. exactly the population least able to match on a
            // structured reference.
            //
            // Best-effort for the same reason the parse path is: an unresolvable creditor should not
            // cost us the invoice, which stands on its own and can be linked by hand later. The same
            // caveat applies — this runs inside the caller's transaction, so a resolver failure that
            // is a *database* error still poisons it, and only a savepoint would change that.
            runCatching { supplierResolver.resolveAndLink(invoice) }
                .onFailure { logger.warn("Supplier resolution failed for invoice {}", invoice.id.value, it) }

            invoice.toDto()
        }

    override suspend fun updateInvoiceSupplierContent(
        invoiceId: Long,
        request: DocumentDto.InvoiceContentRequestDto,
    ): DocumentDto.InvoiceDto =
        dbQuery {
            validated { request.validate() }
            val invoice = repository.getInvoice(invoiceId) ?: throw invoiceNotFound(invoiceId)
            requireNotCancelled(invoice)

            // Supplier-asserted content came from the supplier's own XML; a typed replacement holds
            // nothing better. The operator asked for this explicitly, so silence would mean they
            // retype a supplier's data and are told nothing — this is the live provenance guard.
            if (invoice.extractionSource == ParsedInvoiceWriter.EXTRACTION_SOURCE) {
                throw supplierContentReadOnly(invoiceId)
            }

            val updated =
                repository.updateInvoice(
                    invoiceId = invoiceId,
                    // Provenance travels with the row, not with the request.
                    eInvoiceFormat = invoice.eInvoiceFormat,
                    extractionSource = invoice.extractionSource,
                    invoiceNumber = request.invoiceNumber,
                    issueDate = request.issueDate?.let { parseDate(it) },
                    dueDate = request.dueDate?.let { parseDate(it) },
                    deliveryDate = request.deliveryDate?.let { parseDate(it) },
                    currency = request.currency,
                    invoiceTypeCode = request.invoiceTypeCode,
                    precedingInvoiceReference = request.precedingInvoiceReference,
                    buyerReference = request.buyerReference,
                    sellerName = request.sellerName,
                    sellerVatId = request.sellerVatId,
                    sellerTaxNumber = request.sellerTaxNumber,
                    sellerStreet = request.sellerStreet,
                    sellerZip = request.sellerZip,
                    sellerCity = request.sellerCity,
                    sellerCountry = request.sellerCountry,
                    sellerPhone = request.sellerPhone,
                    sellerEmail = request.sellerEmail,
                    paymentIban = request.paymentIban,
                    paymentReference = request.paymentReference,
                    paymentTermsText = request.paymentTermsText,
                    lineNetTotal = request.lineNetTotal?.toBigDecimalOrNull(),
                    taxExclusiveTotal = request.taxExclusiveTotal?.toBigDecimalOrNull(),
                    taxTotal = request.taxTotal?.toBigDecimalOrNull(),
                    taxInclusiveTotal = request.taxInclusiveTotal?.toBigDecimalOrNull(),
                    amountDue = request.amountDue?.toBigDecimalOrNull(),
                )
            request.vatBreakdown?.let { repository.replaceVatBreakdown(invoiceId, it.toVatRows()) }
            updated.toDto()
        }

    /**
     * Booking-only update: the request carries no supplier-asserted field, and a null field means
     * "leave unchanged" all the way down to [DocumentRepository.upsertInvoiceBooking]. Clearing is
     * explicit, through `clearFields`.
     */
    override suspend fun updateInvoiceBooking(
        invoiceId: Long,
        request: DocumentDto.UpdateInvoiceBookingRequestDto,
    ): DocumentDto.InvoiceDto =
        dbQuery {
            validated { request.validate() }
            val invoice = repository.getInvoice(invoiceId) ?: throw invoiceNotFound(invoiceId)
            requireNotCancelled(invoice)
            val booking =
                repository.upsertInvoiceBooking(
                    invoiceId = invoiceId,
                    accountNo = request.accountNo,
                    bpAccountNo = request.bpAccountNo,
                    buCode = request.buCode,
                    bookingText = request.bookingText,
                    costCategoryId = request.costCategoryId,
                    costCategoryId2 = request.costCategoryId2,
                    costAmount = request.costAmount?.toBigDecimalOrNull(),
                    typeOfReceivable = request.typeOfReceivable,
                    paymentDunningBlock = request.paymentDunningBlock,
                    paidAt = request.paidAt?.let { parseDate(it) },
                    clearFields = request.clearFields?.toSet() ?: emptySet(),
                )
            // Defensive: take the booking straight from the writer rather than through the
            // invoice's back-reference, so the response cannot depend on how Exposed caches a
            // referrer that was created in this same transaction. No staleness has been observed
            // — `InvoiceBookingServiceTest` asserts the booking is visible on the returned DTO
            // either way. (`invoice.refresh()` would not be the alternative: it reloads column
            // values, not referrer caches.)
            invoice.toDto().copy(booking = booking.toDto())
        }

    override suspend fun updateDocumentStatus(
        documentId: Long,
        status: ProcessingStatus,
    ): DocumentDto.DocumentResponseDto =
        dbQuery {
            if (status !in OPERATOR_SETTABLE_STATUSES) {
                throw validationFailed(
                    "processingStatus $status is not settable by an operator; " +
                        "expected one of $OPERATOR_SETTABLE_STATUSES",
                )
            }
            val document =
                repository.updateProcessingStatus(documentId, status) ?: throw documentNotFound(documentId)
            toDto(
                document,
                kassenbuchLinks,
                repository.getInvoiceBySourceDocument(document.id.value),
                repository.getBankStatementBySourceDocument(document.id.value),
            )
        }

    override suspend fun getBankStatement(id: Long): DocumentDto.BankStatementDto =
        dbQuery {
            val statement = repository.getBankStatement(id) ?: throw bankStatementNotFound(id)
            statement.toDto()
        }

    override suspend fun copyDocument(
        sourceDocumentId: Long,
        copiedBy: UUID,
    ): Long {
        val documentStoragePath = configManager.getDocumentStoragePath()

        // Read source metadata (inside a read transaction).
        val source =
            dbQuery {
                repository.findById(sourceDocumentId) ?: throw documentNotFound(sourceDocumentId)
            }
        val sourceFileType = source.fileType
        val sourceMediaType = source.mediaType
        val sourceContentHash = source.contentHash
        val sourceFilename = source.filename
        val sourceAbsolutePath = FileStorageHelper.resolveStoredPath(source.filePath, documentStoragePath)

        // Read source bytes (outside the DB transaction).
        val bytes =
            withContext(Dispatchers.IO) {
                val f = File(sourceAbsolutePath)
                if (!f.exists()) throw documentFileMissing(sourceDocumentId)
                if (!FileStorageHelper.isPathSafe(sourceAbsolutePath, documentStoragePath)) throw fileAccessDenied()
                f.readBytes()
            }

        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

        // Derive a new path. Always use the String overloads, which inject the identifier into
        // the filename, so the copy's filePath is unique even though contentHash matches the
        // source (the enum overload ignores identifier for INVOICE/BANK_STATEMENT → collision).
        val filename = FileStorageHelper.generateFilename(sourceFileType, now, "copy-$sourceDocumentId")
        FileStorageHelper.ensureMonthDirectoryExists(documentStoragePath, sourceFileType, now)
        val absolutePath = FileStorageHelper.generateFilePath(documentStoragePath, sourceFileType, now, filename)
        val relativePath = FileStorageHelper.generateRelativePath(sourceFileType, now, filename)

        withContext(Dispatchers.IO) {
            File(absolutePath).writeBytes(bytes)
        }

        val newId =
            dbQuery {
                repository
                    .create(
                        filename = "copy-of-$sourceFilename",
                        fileType = sourceFileType,
                        filePath = relativePath,
                        fileSize = bytes.size.toLong(),
                        uploadedAt = now,
                        uploadedBy = copiedBy,
                        contentHash = sourceContentHash,
                        mediaType = sourceMediaType,
                    ).id.value
            }
        logger.info("copyDocument: copied document {} -> {}", sourceDocumentId, newId)
        return newId
    }

    // --- PosException helpers ---

    private fun documentNotFound(id: Long) =
        PosException(
            PosErrorCode.RESOURCE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Document with id=$id not found",
        )

    private fun invoiceNotFound(id: Long) =
        PosException(
            PosErrorCode.RESOURCE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Invoice with id=$id not found",
        )

    private fun invoiceNotFoundForDocument(documentId: Long) =
        PosException(
            PosErrorCode.RESOURCE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Document with id=$documentId has no invoice",
        )

    /**
     * 409 rather than 400 or 403: the payload is well-formed and the caller is permitted — it is
     * the invoice's state that forbids the write. Follows `SCHEDULED_JOB_CONFIG_MANAGED`,
     * `BILL_ALREADY_DELETED` and `ITEM_NOT_OPEN`.
     */

    /**
     * T9-19: both halves of a Storno are frozen. Editing the original's number or totals would
     * detach it from the correction that neutralises it, and the correction is ours, not an
     * operator's to retype. The client disables the form; this is the server half of that guard.
     */
    private fun requireNotCancelled(invoice: Invoice) {
        if (!InvoiceStorno.isFrozen(invoice)) return
        throw PosException(
            PosErrorCode.INVOICE_CANCELLED_READ_ONLY,
            HttpStatusCode.Conflict,
            "Invoice with id=${invoice.id.value} is cancelled or is a cancellation; it is read-only",
        )
    }

    private fun supplierContentReadOnly(invoiceId: Long) =
        PosException(
            PosErrorCode.INVOICE_SUPPLIER_CONTENT_READ_ONLY,
            HttpStatusCode.Conflict,
            "Invoice with id=$invoiceId came from a supplier e-invoice; its supplier fields are " +
                "read-only. Booking fields can still be edited.",
        )

    /** Turns a request DTO's `require(...)` into the project's 400 envelope. */
    private inline fun validated(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            throw validationFailed(e.message ?: "Invalid request")
        }
    }

    /**
     * Maps wire VAT rows onto the repository's row type.
     *
     * `String.toBigDecimalOrNull()` here is the **Kotlin stdlib extension**, which preserves the
     * stated scale. Do not reach for `DecimalFormatter`'s `toBigDecimal(value)` /
     * `toBigDecimalOrNull(value)` — those are top-level functions taking a parameter and they pin
     * scale 2, which would round a four-decimal `DECIMAL(19,4)` value on the way in.
     */
    private fun List<DocumentDto.VatBreakdownDto>.toVatRows(): List<DocumentRepository.VatBreakdownRow> =
        map { row ->
            DocumentRepository.VatBreakdownRow(
                vatCategoryCode = row.vatCategoryCode,
                vatRate =
                    row.vatRate.toBigDecimalOrNull()
                        ?: throw validationFailed("vatRate '${row.vatRate}' is not a decimal"),
                taxableAmount =
                    row.taxableAmount.toBigDecimalOrNull()
                        ?: throw validationFailed("taxableAmount '${row.taxableAmount}' is not a decimal"),
                vatAmount =
                    row.vatAmount.toBigDecimalOrNull()
                        ?: throw validationFailed("vatAmount '${row.vatAmount}' is not a decimal"),
                exemptionReasonCode = row.exemptionReasonCode,
                exemptionReasonText = row.exemptionReasonText,
                description = row.description,
            )
        }

    private fun bankStatementNotFound(id: Long) =
        PosException(
            PosErrorCode.RESOURCE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Bank statement with id=$id not found",
        )

    private fun documentLinkNotFound(linkId: Long) =
        PosException(
            PosErrorCode.RESOURCE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Document link with id=$linkId not found",
        )

    private fun documentFileMissing(id: Long) =
        PosException(
            PosErrorCode.RESOURCE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Document file for id=$id not found on disk",
        )

    private fun fileAccessDenied() =
        PosException(
            PosErrorCode.AUTH_FORBIDDEN,
            HttpStatusCode.Forbidden,
            "File access denied (path outside storage root)",
        )

    private fun fileWriteFailed(
        msg: String,
        cause: Throwable? = null,
    ) = PosException(
        PosErrorCode.INTERNAL_ERROR,
        HttpStatusCode.InternalServerError,
        "Document file write failed: $msg",
        cause = cause,
    )

    private fun fileReadFailed(
        msg: String,
        cause: Throwable? = null,
    ) = PosException(
        PosErrorCode.INTERNAL_ERROR,
        HttpStatusCode.InternalServerError,
        "Document file read failed: $msg",
        cause = cause,
    )

    private fun imageTooLarge(maxSizeMb: Int) =
        PosException(
            PosErrorCode.IMAGE_TOO_LARGE,
            HttpStatusCode.PayloadTooLarge,
            "File too large (max ${maxSizeMb}MB)",
        )

    private fun imageTypeUnsupported(expected: String) =
        PosException(
            PosErrorCode.IMAGE_TYPE_UNSUPPORTED,
            HttpStatusCode.UnsupportedMediaType,
            "File type not supported: expected $expected",
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )

    private fun pdfPasswordRequired() =
        PosException(
            PosErrorCode.PDF_PASSWORD_REQUIRED,
            HttpStatusCode.UnprocessableEntity,
            "This PDF needs a password to be unlocked",
        )

    private fun pdfPasswordIncorrect() =
        PosException(
            PosErrorCode.PDF_PASSWORD_INCORRECT,
            HttpStatusCode.UnprocessableEntity,
            "The password does not open this PDF",
        )

    private fun duplicateContent() =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.Conflict,
            "Document with identical content already exists",
        )

    private fun referencedByOther(referencingEntity: String) =
        PosException(
            PosErrorCode.DELETE_HAS_REFERENCES,
            HttpStatusCode.Conflict,
            "Document is referenced by $referencingEntity and cannot be deleted",
        )
}
