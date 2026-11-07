/**
 * Document Repository Implementation
 *
 * Concrete implementation of DocumentRepository using Exposed ORM.
 * Handles all database operations for document metadata.
 *
 * CRITICAL ARCHITECTURE PATTERN:
 * - Repositories do NOT use dbQuery wrapper
 * - Service layer controls transaction boundaries
 * - Returns domain entities (Document)
 * - Exceptions propagate to service layer
 *
 * Type-specific metadata stored in separate tables:
 * - DocumentInvoiceMetadataTable for INVOICE documents
 * - DocumentBankStatementMetadataTable for BANK_STATEMENT documents
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.document.data.repository

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.enums.document.ClearableBookingField
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.features.allocation.database.tables.PaymentAllocationTable
import org.example.project.features.document.database.tables.BankStatementTable
import org.example.project.features.document.database.tables.BankStatementTransactionTable
import org.example.project.features.document.database.tables.DocumentBankStatementMetadataTable
import org.example.project.features.document.database.tables.DocumentInvoiceMetadataTable
import org.example.project.features.document.database.tables.DocumentLinkTable
import org.example.project.features.document.database.tables.DocumentTable
import org.example.project.features.document.database.tables.InvoiceAllowanceChargeTable
import org.example.project.features.document.database.tables.InvoiceBookingTable
import org.example.project.features.document.database.tables.InvoiceLineTable
import org.example.project.features.document.database.tables.InvoiceTable
import org.example.project.features.document.database.tables.InvoiceVatBreakdownTable
import org.example.project.features.document.domain.models.BankStatement
import org.example.project.features.document.domain.models.BankStatementTransaction
import org.example.project.features.document.domain.models.Document
import org.example.project.features.document.domain.models.DocumentBankStatementMetadata
import org.example.project.features.document.domain.models.DocumentInvoiceMetadata
import org.example.project.features.document.domain.models.DocumentLink
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.document.domain.models.InvoiceAllowanceCharge
import org.example.project.features.document.domain.models.InvoiceBooking
import org.example.project.features.document.domain.models.InvoiceLine
import org.example.project.features.document.domain.models.InvoiceVatBreakdown
import org.example.project.features.document.domain.repository.DocumentRepository
import org.example.project.features.document.domain.service.DocumentKassenbuchLinkResolver
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceTable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.entityCache
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.util.UUID
import kotlin.time.Clock

/**
 * Exposed ORM implementation of DocumentRepository.
 */
class DocumentRepositoryImpl : DocumentRepository {
    private val logger = LoggerFactory.getLogger(DocumentRepositoryImpl::class.java)

    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun currentTimestamp(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

    override fun create(
        filename: String,
        fileType: String,
        filePath: String,
        fileSize: Long,
        uploadedAt: LocalDateTime,
        uploadedBy: UUID,
        contentHash: String,
        metadataJson: String?,
        ocrText: String?,
        mediaType: String,
    ): Document {
        val employee =
            Employee.findById(uploadedBy)
                ?: throw IllegalArgumentException("Employee not found: $uploadedBy")

        val document =
            Document.new {
                this.filename = filename
                this.fileType = fileType
                this.filePath = filePath
                this.fileSize = fileSize
                this.uploadedAt = uploadedAt
                this.updatedAt = null
                this.uploadedBy = employee
                this.metadataJson = metadataJson
                this.contentHash = contentHash
                this.isActive = true
                this.ocrText = ocrText
                this.mediaType = mediaType
            }

        logger.info("Created document: id=${document.id.value}, filename=$filename, type=$fileType, mediaType=$mediaType")
        return document
    }

    override fun findById(id: Long): Document? = Document.findById(id)

    override fun findByFilePath(filePath: String): Document? =
        Document
            .find {
                DocumentTable.filePath eq filePath
            }.firstOrNull()

    override fun findByContentHash(contentHash: String): Document? =
        Document
            .find {
                (DocumentTable.contentHash eq contentHash) and (DocumentTable.isActive eq true)
            }.firstOrNull()

    override fun search(
        fileType: String?,
        fromDate: LocalDateTime?,
        toDate: LocalDateTime?,
        month: String?,
        uploadedBy: UUID?,
        activeOnly: Boolean,
        searchTerm: String?,
        vendorName: String?,
        invoiceNumber: String?,
        minAmount: BigDecimal?,
        maxAmount: BigDecimal?,
        bankName: String?,
        statementNumber: String?,
        processingStatuses: List<ProcessingStatus>?,
        offset: Int,
        limit: Int,
    ): List<Document> {
        val query =
            buildSearchQuery(
                fileType,
                fromDate,
                toDate,
                month,
                uploadedBy,
                activeOnly,
                searchTerm,
                vendorName,
                invoiceNumber,
                minAmount,
                maxAmount,
                bankName,
                statementNumber,
                processingStatuses,
            )

        query
            .limit(limit)
            .offset(offset.toLong())
            .orderBy(DocumentTable.uploadedAt to SortOrder.DESC)

        return Document.wrapRows(query).toList()
    }

    override fun count(
        fileType: String?,
        fromDate: LocalDateTime?,
        toDate: LocalDateTime?,
        month: String?,
        uploadedBy: UUID?,
        activeOnly: Boolean,
        searchTerm: String?,
        vendorName: String?,
        invoiceNumber: String?,
        minAmount: BigDecimal?,
        maxAmount: BigDecimal?,
        bankName: String?,
        statementNumber: String?,
        processingStatuses: List<ProcessingStatus>?,
    ): Long {
        val query =
            buildSearchQuery(
                fileType,
                fromDate,
                toDate,
                month,
                uploadedBy,
                activeOnly,
                searchTerm,
                vendorName,
                invoiceNumber,
                minAmount,
                maxAmount,
                bankName,
                statementNumber,
                processingStatuses,
            )

        return query.count()
    }

    override fun softDelete(id: Long) {
        val document =
            Document.findById(id)
                ?: throw NoSuchElementException("Document not found: $id")
        document.isActive = false
        document.updatedAt = currentTimestamp()
    }

    override fun reactivate(id: Long) {
        val document =
            Document.findById(id)
                ?: throw NoSuchElementException("Document not found: $id")
        document.isActive = true
        document.updatedAt = currentTimestamp()
    }

    override fun updateMetadata(
        id: Long,
        metadataJson: String,
    ): Document? {
        val document = Document.findById(id) ?: return null
        document.metadataJson = metadataJson
        document.updatedAt = currentTimestamp()
        return document
    }

    override fun updateProcessingStatus(
        id: Long,
        status: ProcessingStatus,
    ): Document? {
        val document = Document.findById(id) ?: return null
        document.processingStatus = status.name
        document.updatedAt = currentTimestamp()
        return document
    }

    override fun getStorageStatistics(): Map<String, Long> {
        val totalDocuments = Document.find { DocumentTable.isActive eq true }.count()

        val invoiceCount =
            Document
                .find {
                    (DocumentTable.fileType eq "INVOICE") and (DocumentTable.isActive eq true)
                }.count()

        val bankStatementCount =
            Document
                .find {
                    (DocumentTable.fileType eq "BANK_STATEMENT") and (DocumentTable.isActive eq true)
                }.count()

        val contractsCount =
            Document
                .find {
                    (DocumentTable.fileType eq "CONTRACTS") and (DocumentTable.isActive eq true)
                }.count()

        // Count documents that are not standard types (includes CUSTOM and any custom type names)
        val standardTypes = listOf("INVOICE", "BANK_STATEMENT", "CONTRACTS", "EIGENBELEG", "ZBON_REPORT")
        val customCount =
            Document
                .find {
                    (DocumentTable.fileType notInList standardTypes) and (DocumentTable.isActive eq true)
                }.count()

        val totalStorageBytes =
            DocumentTable
                .select(DocumentTable.fileSize.sum())
                .where { DocumentTable.isActive eq true }
                .map { it[DocumentTable.fileSize.sum()] ?: 0L }
                .singleOrNull() ?: 0L

        return mapOf(
            "totalDocuments" to totalDocuments,
            "invoiceCount" to invoiceCount,
            "bankStatementCount" to bankStatementCount,
            "contractsCount" to contractsCount,
            "customCount" to customCount,
            "totalStorageBytes" to totalStorageBytes,
        )
    }

    /**
     * `EXISTS (SELECT 1 FROM invoice WHERE invoice.source_document_id = document.id AND [condition])`.
     *
     * Correlated on purpose. The aggregate is inverted relative to the legacy metadata tables — the
     * invoice references the document — so the equivalent join would be
     * `DocumentTable leftJoin InvoiceTable`, which emits one row per invoice and would both
     * duplicate search hits and inflate `count` if a document ever gained a second invoice.
     * `EXISTS` also keeps a document with no invoice untouched by any filter that does not mention
     * one, and an invoice with a null `source_document_id` simply matches nothing rather than
     * hiding a document.
     *
     * ### Cross-filter semantics once a document can have several invoices
     * Each filter gets its **own** `EXISTS`, so `vendorName` + `invoiceNumber` can match the vendor
     * on invoice A and the number on invoice B of the same document — where the old single join
     * required both on one row. `minAmount` + `maxAmount` can likewise straddle two invoices.
     * Unreachable today (`InvoiceBackfill` skips a document that already has an invoice, so it stays
     * strictly 1:1 via `getInvoiceBySourceDocument`), and
     * reachable exactly when the multi-invoice import that motivates `EXISTS` lands. Consolidating
     * the filters into one ANDed `EXISTS` would change that behaviour, so it is left as a
     * deliberate decision for whoever ships multi-invoice rather than a speculative one now.
     */
    private fun invoiceMatches(condition: Op<Boolean>): Op<Boolean> =
        exists(
            InvoiceTable
                .select(InvoiceTable.id)
                .where { (InvoiceTable.sourceDocumentId eq DocumentTable.id) and condition },
        )

    /**
     * The date a document is filed under: what the list's "Datum" column shows — the invoice date
     * (normalized invoice first, then the frozen legacy metadata), else the bank-statement date
     * (normalized, then legacy) — and the **upload date** for a document that has none (a plain scan).
     * The month filter and the `fromDate`/`toDate` bounds all use this one expression, so they cannot
     * disagree with each other or with the column.
     *
     * Correlated scalar subqueries (`MIN` because a document may hold several invoices), evaluated
     * only when a period filter is present. The list mapper picks whole-record normalized-before-legacy;
     * here the legs coalesce per field, which differs only for a normalized invoice with no date next
     * to a legacy row that has one — legacy is frozen and `InvoiceBackfill` copies its date.
     */
    private fun documentDateExpression(): ExpressionWithColumnType<LocalDateTime?> =
        CustomFunction<LocalDateTime?>(
            "COALESCE",
            DocumentTable.uploadedAt.columnType,
            wrapAsExpression<LocalDateTime>(
                InvoiceTable.select(InvoiceTable.issueDate.min()).where { InvoiceTable.sourceDocumentId eq DocumentTable.id },
            ),
            wrapAsExpression<LocalDateTime>(
                DocumentInvoiceMetadataTable
                    .select(DocumentInvoiceMetadataTable.invoiceDate.min())
                    .where { DocumentInvoiceMetadataTable.documentId eq DocumentTable.id },
            ),
            wrapAsExpression<LocalDateTime>(
                BankStatementTable
                    .select(BankStatementTable.statementDate.min())
                    .where { BankStatementTable.sourceDocumentId eq DocumentTable.id },
            ),
            wrapAsExpression<LocalDateTime>(
                DocumentBankStatementMetadataTable
                    .select(DocumentBankStatementMetadataTable.statementDate.min())
                    .where { DocumentBankStatementMetadataTable.documentId eq DocumentTable.id },
            ),
            DocumentTable.uploadedAt,
        )

    /**
     * Builds search query with filters including expanded search across
     * filename, OCR text, and type-specific metadata fields.
     *
     * Both [search] and [count] call this and nothing else, so their predicates cannot drift; a
     * filter added here reaches the page and its total together. Do not inline it into either.
     *
     * ### bankStatementFiltersStayLegacy
     * `vendorName` / `invoiceNumber` and the invoice leg of `minAmount` / `maxAmount` read the
     * normalized [InvoiceTable] — new invoices are written there by `ParsedInvoiceWriter` and the
     * capture path, and pre-migration rows by `InvoiceBackfill`, so it is complete. There are no
     * legacy writes left to project: `document_invoice_metadata` is frozen. `bankName` /
     * `statementNumber` and the balance leg deliberately still read
     * [DocumentBankStatementMetadataTable]: nothing in production writes [BankStatementTable] yet
     * (no sync, no backfill — `createBankStatement` has test callers only), so repointing them
     * would silently return zero rows for every bank-statement search. Move them once the
     * bank-statement write path lands.
     *
     * ### processingStatusIsASet
     * [processingStatuses] is an `IN` list, because adminApp's work queue buckets are sets:
     * Zu erfassen = NONE/UNPROCESSABLE/FAILED, Zu prüfen = COMPLETED, Fertig = REVIEWED. This was a
     * single-value equality test when it landed (Stage 3c-1); one request per status does not
     * survive paging, since `offset`/`limit` are per-request and a client-side merge of three
     * responses cannot produce a coherent page 2, `total` or `hasMore`. Null and empty both mean
     * "every status" — see the predicate's comment for why empty is not "match nothing".
     * V87's `(processing_status, uploaded_at)` index still serves this: `processing_status` is the
     * leading column, which an `IN` predicate uses the same way an `=` does.
     */
    private fun buildSearchQuery(
        fileType: String?,
        fromDate: LocalDateTime?,
        toDate: LocalDateTime?,
        month: String?,
        uploadedBy: UUID?,
        activeOnly: Boolean,
        searchTerm: String? = null,
        vendorName: String? = null,
        invoiceNumber: String? = null,
        minAmount: BigDecimal? = null,
        maxAmount: BigDecimal? = null,
        bankName: String? = null,
        statementNumber: String? = null,
        processingStatuses: List<ProcessingStatus>? = null,
    ): Query {
        // The invoice filters read the normalized model, which *references* the document rather
        // than hanging off it, so they cannot be expressed as a join without risking duplicate
        // document rows (`source_document_id` is a plain index, not unique). A correlated EXISTS
        // keeps `search` one row per document and its `count` in agreement with it.
        //
        // The bank-statement filters stay on the legacy metadata table on purpose — see the
        // `bankStatementFiltersStayLegacy` note below.
        val needsBankStatementJoin =
            !bankName.isNullOrBlank() ||
                !statementNumber.isNullOrBlank() ||
                !searchTerm.isNullOrBlank() ||
                minAmount != null ||
                maxAmount != null

        var baseQuery: ColumnSet = DocumentTable as ColumnSet

        if (needsBankStatementJoin) {
            baseQuery =
                baseQuery.leftJoin(
                    DocumentBankStatementMetadataTable,
                    { DocumentTable.id },
                    { DocumentBankStatementMetadataTable.documentId },
                )
        }

        return baseQuery.select(DocumentTable.columns).apply {
            if (activeOnly) {
                andWhere { DocumentTable.isActive eq true }
            }

            if (fileType != null) {
                andWhere { DocumentTable.fileType eq fileType }
            }

            // The work queue's predicate. It lives on DocumentTable itself, so — unlike the invoice
            // filters below — it needs neither the bank-statement join nor a correlated EXISTS.
            // The column stores the enum's constant name (DocumentMapper reads it back with a bare
            // ProcessingStatus.valueOf()), so compare against `.name`.
            //
            // isNullOrEmpty, not != null: an empty list is "no filter", identical to null. The
            // opposite reading ("match nothing") would give the UI an inescapable empty screen with
            // every filter visibly off, and Exposed's inList(emptyList()) is a degenerate condition
            // nobody should have to reason about.
            if (!processingStatuses.isNullOrEmpty()) {
                andWhere { DocumentTable.processingStatus inList processingStatuses.map { it.name } }
            }

            // The period filters work on the DOCUMENT date — the one the list's "Datum" column shows —
            // not on the upload date. See [documentDateExpression].
            if (fromDate != null) {
                andWhere { documentDateExpression() greaterEq fromDate }
            }
            if (toDate != null) {
                andWhere { documentDateExpression() lessEq toDate }
            }

            if (month != null) {
                val parts = month.split("-")
                if (parts.size == 2) {
                    val year = parts[0].toIntOrNull()
                    val monthNum = parts[1].toIntOrNull()
                    if (year != null && monthNum != null) {
                        val monthStart = LocalDateTime(year, monthNum, 1, 0, 0)
                        val monthEnd =
                            if (monthNum == 12) {
                                LocalDateTime(year + 1, 1, 1, 0, 0)
                            } else {
                                LocalDateTime(year, monthNum + 1, 1, 0, 0)
                            }
                        andWhere {
                            (documentDateExpression() greaterEq monthStart) and
                                (documentDateExpression() less monthEnd)
                        }
                    }
                }
            }

            if (uploadedBy != null) {
                andWhere { DocumentTable.uploadedBy eq uploadedBy }
            }

            // Expanded search across filename, OCR text, vendor name, and bank name
            if (!searchTerm.isNullOrBlank()) {
                val term = "%${searchTerm.lowercase()}%"
                andWhere {
                    var condition: Op<Boolean> = DocumentTable.filename.lowerCase() like term
                    condition = condition or (DocumentTable.ocrText.lowerCase() like term)
                    condition = condition or invoiceMatches(InvoiceTable.sellerName.lowerCase() like term)
                    if (needsBankStatementJoin) {
                        condition = condition or (DocumentBankStatementMetadataTable.bankName.lowerCase() like term)
                    }
                    condition
                }
            }

            // Type-specific metadata filters
            if (!vendorName.isNullOrBlank()) {
                andWhere {
                    invoiceMatches(InvoiceTable.sellerName.lowerCase() like "%${vendorName.lowercase()}%")
                }
            }

            if (!invoiceNumber.isNullOrBlank()) {
                andWhere {
                    invoiceMatches(InvoiceTable.invoiceNumber eq invoiceNumber)
                }
            }

            if (minAmount != null) {
                andWhere {
                    invoiceMatches(InvoiceTable.taxInclusiveTotal greaterEq minAmount) or
                        (DocumentBankStatementMetadataTable.closingBalance greaterEq minAmount)
                }
            }

            if (maxAmount != null) {
                andWhere {
                    invoiceMatches(InvoiceTable.taxInclusiveTotal lessEq maxAmount) or
                        (DocumentBankStatementMetadataTable.closingBalance lessEq maxAmount)
                }
            }

            if (!bankName.isNullOrBlank()) {
                andWhere {
                    DocumentBankStatementMetadataTable.bankName.lowerCase() like "%${bankName.lowercase()}%"
                }
            }

            if (!statementNumber.isNullOrBlank()) {
                andWhere {
                    DocumentBankStatementMetadataTable.statementNumber eq statementNumber
                }
            }
        }
    }

    // ===== DOCUMENT LINKING OPERATIONS =====

    override fun createLink(
        documentId: Long,
        bankStatementDocumentId: Long,
        linkedBy: UUID,
        linkedAt: LocalDateTime,
        notes: String?,
    ): DocumentLink {
        val doc =
            Document.findById(documentId)
                ?: throw NoSuchElementException("Document not found: $documentId")

        val bankStatement =
            Document.findById(bankStatementDocumentId)
                ?: throw NoSuchElementException("Document not found: $bankStatementDocumentId")
        if (!bankStatement.isBankStatement()) {
            throw IllegalArgumentException("Document must be of type BANK_STATEMENT")
        }

        // Check for duplicate link
        val existingLink =
            DocumentLink
                .find {
                    (DocumentLinkTable.documentId eq documentId) and
                        (DocumentLinkTable.bankStatementDocumentId eq bankStatementDocumentId)
                }.firstOrNull()

        if (existingLink != null) {
            throw IllegalStateException("Link already exists between these documents")
        }

        val link =
            DocumentLink.new {
                this.document = doc
                this.bankStatementDocument = bankStatement
                this.linkedBy = linkedBy.toString()
                this.linkedAt = linkedAt
                this.notes = notes
            }

        logger.info("Created document link: id=${link.id.value}")
        return link
    }

    override fun getLinkedDocuments(documentId: Long): List<DocumentLink> {
        val asDocument =
            DocumentLink
                .find {
                    DocumentLinkTable.documentId eq documentId
                }.toList()

        val asBankStatement =
            DocumentLink
                .find {
                    DocumentLinkTable.bankStatementDocumentId eq documentId
                }.toList()

        return asDocument + asBankStatement
    }

    override fun deleteLink(linkId: Long) {
        val link =
            DocumentLink.findById(linkId)
                ?: throw NoSuchElementException("DocumentLink not found: $linkId")
        link.delete()
    }

    override fun getUnlinkedDocuments(
        fileType: String?,
        activeOnly: Boolean,
        offset: Int,
        limit: Int,
        search: String?,
        startDate: String?,
        endDate: String?,
        excludeTypes: List<String>,
    ): List<Document> {
        val allDocumentsQuery =
            Document.find {
                var condition =
                    if (fileType != null) {
                        DocumentTable.fileType eq fileType
                    } else {
                        DocumentTable.id.isNotNull() // match all
                    }
                if (excludeTypes.isNotEmpty()) {
                    condition = condition and (DocumentTable.fileType notInList excludeTypes)
                }
                if (activeOnly) {
                    condition = condition and (DocumentTable.isActive eq true)
                }
                if (!search.isNullOrBlank()) {
                    condition = condition and (DocumentTable.filename.lowerCase() like "%${search.lowercase()}%")
                }
                startDate?.let { dateStr ->
                    val fromDate =
                        try {
                            LocalDateTime.parse(dateStr)
                        } catch (_: Exception) {
                            LocalDateTime.parse("${dateStr}T00:00:00")
                        }
                    condition = condition and (DocumentTable.uploadedAt greaterEq fromDate)
                }
                endDate?.let { dateStr ->
                    val toDate =
                        try {
                            LocalDateTime.parse(dateStr)
                        } catch (_: Exception) {
                            LocalDateTime.parse("${dateStr}T23:59:59")
                        }
                    condition = condition and (DocumentTable.uploadedAt lessEq toDate)
                }
                condition
            }

        // Bank statements can be linked to multiple documents, so skip filtering for them
        val skipLinkFilter = fileType == "BANK_STATEMENT"

        return if (skipLinkFilter) {
            allDocumentsQuery
                .sortedByDescending { it.uploadedAt }
                .drop(offset)
                .take(limit)
        } else {
            val linkedAsDocument = DocumentLink.all().map { it.document.id.value }.toSet()
            val linkedAsBankStatement = DocumentLink.all().map { it.bankStatementDocument.id.value }.toSet()
            val linkedToKassenbuch =
                KassenbuchHeaderTable
                    .select(KassenbuchHeaderTable.linkedDocumentId)
                    .where { KassenbuchHeaderTable.linkedDocumentId.isNotNull() }
                    .mapNotNull { it[KassenbuchHeaderTable.linkedDocumentId] }
                    .toSet()
            val linkedIds = linkedAsDocument + linkedAsBankStatement + linkedToKassenbuch

            allDocumentsQuery
                .filter { it.id.value !in linkedIds }
                .sortedByDescending { it.uploadedAt }
                .drop(offset)
                .take(limit)
        }
    }

    // ===== INVOICE METADATA OPERATIONS =====

    override fun createInvoiceMetadata(
        documentId: Long,
        vendorName: String?,
        invoiceNumber: String?,
        invoiceDate: LocalDateTime?,
        dueDate: LocalDateTime?,
        netAmount: BigDecimal?,
        grossAmount: BigDecimal?,
        taxAmount: BigDecimal?,
        taxRate: BigDecimal?,
        currency: String?,
        iban: String?,
        paymentReference: String?,
        supplierCity: String?,
        vatId: String?,
        accountNo: String?,
        bpAccountNo: String?,
        deliveryDate: LocalDateTime?,
        bookingText: String?,
        taxLineItemsJson: String?,
        lineItemsJson: String?,
        paymentDetailsJson: String?,
        discountTermsJson: String?,
        invoiceType: String?,
        draweeNo: String?,
        orderId: String?,
        supplierStreet: String?,
        supplierZip: String?,
        supplierCountry: String?,
        supplierPhone: String?,
        supplierEmail: String?,
        shipToCountry: String?,
        shipFromCountry: String?,
        paymentConditionsText: String?,
        paidAt: LocalDateTime?,
        paymentDunningBlock: Boolean?,
        costCategoryId: String?,
        costCategoryId2: String?,
        costAmount: BigDecimal?,
        exchangeRate: BigDecimal?,
        euTaxRate: BigDecimal?,
        buCode: String?,
        typeOfReceivable: String?,
        deliveryDateStart: LocalDateTime?,
        deliveryDateEnd: LocalDateTime?,
        additionalInfoJson: String?,
    ): DocumentInvoiceMetadata {
        val document =
            Document.findById(documentId)
                ?: throw NoSuchElementException("Document not found: $documentId")

        val existing =
            DocumentInvoiceMetadata
                .find {
                    DocumentInvoiceMetadataTable.documentId eq documentId
                }.firstOrNull()

        if (existing != null) {
            throw IllegalStateException("Invoice metadata already exists for document: $documentId")
        }

        // Preventive validation: DATEV XSD limits invoiceId to 20 chars
        if (invoiceNumber != null && invoiceNumber.length > 20) {
            logger.warn(
                "Invoice number '{}' exceeds DATEV 20-char limit ({}), will be truncated at export",
                invoiceNumber,
                invoiceNumber.length,
            )
        }

        val metadata =
            DocumentInvoiceMetadata.new {
                this.document = document
                this.vendorName = vendorName
                this.invoiceNumber = invoiceNumber
                this.invoiceDate = invoiceDate
                this.dueDate = dueDate
                this.netAmount = netAmount
                this.grossAmount = grossAmount
                this.taxAmount = taxAmount
                this.taxRate = taxRate
                if (currency != null) this.currency = currency
                this.iban = iban
                this.paymentReference = paymentReference
                this.supplierCity = supplierCity
                this.vatId = vatId
                this.accountNo = accountNo
                this.bpAccountNo = bpAccountNo
                this.deliveryDate = deliveryDate
                this.bookingText = bookingText
                this.taxLineItemsJson = taxLineItemsJson
                this.lineItemsJson = lineItemsJson
                this.paymentDetailsJson = paymentDetailsJson
                this.discountTermsJson = discountTermsJson
                this.invoiceType = invoiceType
                this.draweeNo = draweeNo
                this.orderId = orderId
                this.supplierStreet = supplierStreet
                this.supplierZip = supplierZip
                this.supplierCountry = supplierCountry
                this.supplierPhone = supplierPhone
                this.supplierEmail = supplierEmail
                this.shipToCountry = shipToCountry
                this.shipFromCountry = shipFromCountry
                this.paymentConditionsText = paymentConditionsText
                this.paidAt = paidAt
                this.paymentDunningBlock = paymentDunningBlock ?: false
                this.costCategoryId = costCategoryId
                this.costCategoryId2 = costCategoryId2
                this.costAmount = costAmount
                this.exchangeRate = exchangeRate
                this.euTaxRate = euTaxRate
                this.buCode = buCode
                this.typeOfReceivable = typeOfReceivable
                this.deliveryDateStart = deliveryDateStart
                this.deliveryDateEnd = deliveryDateEnd
                this.additionalInfoJson = additionalInfoJson
            }

        logger.info("Created invoice metadata for document: id=$documentId")
        return metadata
    }

    override fun updateInvoiceMetadata(
        documentId: Long,
        vendorName: String?,
        invoiceNumber: String?,
        invoiceDate: LocalDateTime?,
        dueDate: LocalDateTime?,
        netAmount: BigDecimal?,
        grossAmount: BigDecimal?,
        taxAmount: BigDecimal?,
        taxRate: BigDecimal?,
        currency: String?,
        iban: String?,
        paymentReference: String?,
        supplierCity: String?,
        vatId: String?,
        accountNo: String?,
        bpAccountNo: String?,
        deliveryDate: LocalDateTime?,
        bookingText: String?,
        taxLineItemsJson: String?,
        lineItemsJson: String?,
        paymentDetailsJson: String?,
        discountTermsJson: String?,
        invoiceType: String?,
        draweeNo: String?,
        orderId: String?,
        supplierStreet: String?,
        supplierZip: String?,
        supplierCountry: String?,
        supplierPhone: String?,
        supplierEmail: String?,
        shipToCountry: String?,
        shipFromCountry: String?,
        paymentConditionsText: String?,
        paidAt: LocalDateTime?,
        paymentDunningBlock: Boolean?,
        costCategoryId: String?,
        costCategoryId2: String?,
        costAmount: BigDecimal?,
        exchangeRate: BigDecimal?,
        euTaxRate: BigDecimal?,
        buCode: String?,
        typeOfReceivable: String?,
        deliveryDateStart: LocalDateTime?,
        deliveryDateEnd: LocalDateTime?,
        additionalInfoJson: String?,
    ): DocumentInvoiceMetadata {
        val document =
            Document.findById(documentId)
                ?: throw NoSuchElementException("Document not found: $documentId")

        val metadata =
            DocumentInvoiceMetadata
                .find {
                    DocumentInvoiceMetadataTable.documentId eq documentId
                }.firstOrNull() ?: DocumentInvoiceMetadata.new {
                this.document = document
            }

        // Preventive validation: DATEV XSD limits invoiceId to 20 chars
        if (invoiceNumber != null && invoiceNumber.length > 20) {
            logger.warn(
                "Invoice number '{}' exceeds DATEV 20-char limit ({}), will be truncated at export",
                invoiceNumber,
                invoiceNumber.length,
            )
        }

        metadata.vendorName = vendorName
        metadata.invoiceNumber = invoiceNumber
        metadata.invoiceDate = invoiceDate
        metadata.dueDate = dueDate
        metadata.netAmount = netAmount
        metadata.grossAmount = grossAmount
        metadata.taxAmount = taxAmount
        metadata.taxRate = taxRate
        if (currency != null) metadata.currency = currency
        metadata.iban = iban
        metadata.paymentReference = paymentReference
        metadata.supplierCity = supplierCity
        metadata.vatId = vatId
        metadata.accountNo = accountNo
        metadata.bpAccountNo = bpAccountNo
        metadata.deliveryDate = deliveryDate
        metadata.bookingText = bookingText
        metadata.taxLineItemsJson = taxLineItemsJson
        metadata.lineItemsJson = lineItemsJson
        metadata.paymentDetailsJson = paymentDetailsJson
        metadata.discountTermsJson = discountTermsJson
        metadata.invoiceType = invoiceType
        metadata.draweeNo = draweeNo
        metadata.orderId = orderId
        metadata.supplierStreet = supplierStreet
        metadata.supplierZip = supplierZip
        metadata.supplierCountry = supplierCountry
        metadata.supplierPhone = supplierPhone
        metadata.supplierEmail = supplierEmail
        metadata.shipToCountry = shipToCountry
        metadata.shipFromCountry = shipFromCountry
        metadata.paymentConditionsText = paymentConditionsText
        metadata.paidAt = paidAt
        metadata.paymentDunningBlock = paymentDunningBlock ?: false
        metadata.costCategoryId = costCategoryId
        metadata.costCategoryId2 = costCategoryId2
        metadata.costAmount = costAmount
        metadata.exchangeRate = exchangeRate
        metadata.euTaxRate = euTaxRate
        metadata.buCode = buCode
        metadata.typeOfReceivable = typeOfReceivable
        metadata.deliveryDateStart = deliveryDateStart
        metadata.deliveryDateEnd = deliveryDateEnd
        metadata.additionalInfoJson = additionalInfoJson

        document.updatedAt = currentTimestamp()

        logger.info("Updated invoice metadata for document: id=$documentId")
        return metadata
    }

    override fun getInvoicesForDatevApExport(
        startDate: String,
        endDate: String,
        excludeKassenbuchLinked: Boolean,
        kassenbuchLinks: DocumentKassenbuchLinkResolver,
    ): List<Invoice> {
        val parsedStart = kotlinx.datetime.LocalDate.parse(startDate)
        val startDateTime = LocalDateTime(parsedStart.year, parsedStart.month, parsedStart.day, 0, 0, 0)
        val parsedEnd = kotlinx.datetime.LocalDate.parse(endDate)
        val endDateTime = LocalDateTime(parsedEnd.year, parsedEnd.month, parsedEnd.day, 23, 59, 59)

        // Date-bounded in SQL. The soft-delete check rides along on a left join so an invoice with
        // no source document (bare XRechnung XML) is still returned. The old `fileType = "INVOICE"`
        // filter is gone and currently harmless: an invoice row only ever originates from an
        // INVOICE document, so the predicate could never exclude anything here.
        val rows =
            InvoiceTable
                .leftJoin(DocumentTable, { InvoiceTable.sourceDocumentId }, { DocumentTable.id })
                .select(InvoiceTable.columns)
                .where {
                    InvoiceTable.issueDate.between(startDateTime, endDateTime) and
                        (InvoiceTable.sourceDocumentId.isNull() or (DocumentTable.isActive eq true))
                }.orderBy(InvoiceTable.id to SortOrder.ASC)

        // T9-19: a cancelled invoice and its correction are no booking to pass on — the pair nets
        // to zero, and the original is not an open item. Both stay archived and visible here.
        val loaded = Invoice.wrapRows(rows).toList()
        val cancelled = InvoiceStorno.frozenIdsAmong(loaded)
        val invoices = loaded.filterNot { it.id.value in cancelled }
        if (!excludeKassenbuchLinked) return invoices

        // Drop invoices whose source document is booked through the Kassenbuch (cash payments are
        // already covered by the Kassenbuch export).
        return invoices.filter { invoice ->
            val documentId = invoice.sourceDocument?.id?.value ?: return@filter true
            kassenbuchLinks.countLinks(documentId) == 0
        }
    }

    override fun getInvoiceMetadata(documentId: Long): DocumentInvoiceMetadata? =
        DocumentInvoiceMetadata
            .find {
                DocumentInvoiceMetadataTable.documentId eq documentId
            }.firstOrNull()

    // ===== BANK STATEMENT METADATA OPERATIONS =====

    override fun createBankStatementMetadata(
        documentId: Long,
        bankName: String?,
        bic: String?,
        iban: String?,
        accountNumber: String?,
        statementNumber: String?,
        statementDate: LocalDateTime?,
        periodStart: LocalDateTime?,
        periodEnd: LocalDateTime?,
        openingBalance: BigDecimal?,
        closingBalance: BigDecimal?,
        currency: String?,
        transactionsJson: String?,
    ): DocumentBankStatementMetadata {
        val document =
            Document.findById(documentId)
                ?: throw NoSuchElementException("Document not found: $documentId")

        val existing =
            DocumentBankStatementMetadata
                .find {
                    DocumentBankStatementMetadataTable.documentId eq documentId
                }.firstOrNull()

        if (existing != null) {
            throw IllegalStateException("Bank statement metadata already exists for document: $documentId")
        }

        val metadata =
            DocumentBankStatementMetadata.new {
                this.document = document
                this.bankName = bankName
                this.bic = bic
                this.iban = iban
                this.accountNumber = accountNumber
                this.statementNumber = statementNumber
                this.statementDate = statementDate
                this.periodStart = periodStart
                this.periodEnd = periodEnd
                this.openingBalance = openingBalance
                this.closingBalance = closingBalance
                if (currency != null) this.currency = currency
                this.transactionsJson = transactionsJson
            }

        logger.info("Created bank statement metadata for document: id=$documentId")
        return metadata
    }

    override fun updateBankStatementMetadata(
        documentId: Long,
        bankName: String?,
        bic: String?,
        iban: String?,
        accountNumber: String?,
        statementNumber: String?,
        statementDate: LocalDateTime?,
        periodStart: LocalDateTime?,
        periodEnd: LocalDateTime?,
        openingBalance: BigDecimal?,
        closingBalance: BigDecimal?,
        currency: String?,
        transactionsJson: String?,
    ): DocumentBankStatementMetadata {
        val document =
            Document.findById(documentId)
                ?: throw NoSuchElementException("Document not found: $documentId")

        val metadata =
            DocumentBankStatementMetadata
                .find {
                    DocumentBankStatementMetadataTable.documentId eq documentId
                }.firstOrNull() ?: DocumentBankStatementMetadata.new {
                this.document = document
            }

        metadata.bankName = bankName
        metadata.bic = bic
        metadata.iban = iban
        metadata.accountNumber = accountNumber
        metadata.statementNumber = statementNumber
        metadata.statementDate = statementDate
        metadata.periodStart = periodStart
        metadata.periodEnd = periodEnd
        metadata.openingBalance = openingBalance
        metadata.closingBalance = closingBalance
        if (currency != null) metadata.currency = currency
        metadata.transactionsJson = transactionsJson

        document.updatedAt = currentTimestamp()

        logger.info("Updated bank statement metadata for document: id=$documentId")
        return metadata
    }

    override fun getBankStatementMetadata(documentId: Long): DocumentBankStatementMetadata? =
        DocumentBankStatementMetadata
            .find {
                DocumentBankStatementMetadataTable.documentId eq documentId
            }.firstOrNull()

    override fun deleteBankStatementMetadata(documentId: Long) {
        val metadata =
            DocumentBankStatementMetadata
                .find {
                    DocumentBankStatementMetadataTable.documentId eq documentId
                }.firstOrNull()
                ?: throw NoSuchElementException("Bank statement metadata not found for document: $documentId")
        metadata.delete()
    }

    // ===== INVOICE AGGREGATE (normalized model) =====

    override fun createInvoice(
        sourceDocumentId: Long?,
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
    ): Invoice {
        val source =
            sourceDocumentId?.let {
                Document.findById(it) ?: throw NoSuchElementException("Document not found: $it")
            }

        return Invoice.new {
            this.sourceDocument = source
            this.eInvoiceFormat = eInvoiceFormat
            this.extractionSource = extractionSource
            this.invoiceNumber = invoiceNumber
            this.issueDate = issueDate
            this.dueDate = dueDate
            this.deliveryDate = deliveryDate
            this.currency = currency
            this.invoiceTypeCode = invoiceTypeCode
            this.precedingInvoiceReference = precedingInvoiceReference
            this.buyerReference = buyerReference
            this.sellerName = sellerName
            this.sellerVatId = sellerVatId
            this.sellerTaxNumber = sellerTaxNumber
            this.sellerStreet = sellerStreet
            this.sellerZip = sellerZip
            this.sellerCity = sellerCity
            this.sellerCountry = sellerCountry
            this.sellerPhone = sellerPhone
            this.sellerEmail = sellerEmail
            this.paymentIban = paymentIban
            this.paymentReference = paymentReference
            this.paymentTermsText = paymentTermsText
            this.lineNetTotal = lineNetTotal
            this.taxExclusiveTotal = taxExclusiveTotal
            this.taxTotal = taxTotal
            this.taxInclusiveTotal = taxInclusiveTotal
            this.amountDue = amountDue
            this.createdAt = currentTimestamp()
            this.updatedAt = null
        }
    }

    /**
     * Full replace of the supplier-asserted header — every listed field is written, `null`
     * included. See the interface KDoc for why a partial edit must not use this.
     */
    override fun updateInvoice(
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
    ): Invoice {
        val invoice = requireInvoice(invoiceId)

        invoice.eInvoiceFormat = eInvoiceFormat
        invoice.extractionSource = extractionSource
        invoice.invoiceNumber = invoiceNumber
        invoice.issueDate = issueDate
        invoice.dueDate = dueDate
        invoice.deliveryDate = deliveryDate
        invoice.currency = currency
        invoice.invoiceTypeCode = invoiceTypeCode
        invoice.precedingInvoiceReference = precedingInvoiceReference
        invoice.buyerReference = buyerReference
        invoice.sellerName = sellerName
        invoice.sellerVatId = sellerVatId
        invoice.sellerTaxNumber = sellerTaxNumber
        invoice.sellerStreet = sellerStreet
        invoice.sellerZip = sellerZip
        invoice.sellerCity = sellerCity
        invoice.sellerCountry = sellerCountry
        invoice.sellerPhone = sellerPhone
        invoice.sellerEmail = sellerEmail
        invoice.paymentIban = paymentIban
        invoice.paymentReference = paymentReference
        invoice.paymentTermsText = paymentTermsText
        invoice.lineNetTotal = lineNetTotal
        invoice.taxExclusiveTotal = taxExclusiveTotal
        invoice.taxTotal = taxTotal
        invoice.taxInclusiveTotal = taxInclusiveTotal
        invoice.amountDue = amountDue
        touchInvoice(invoice)

        return invoice
    }

    override fun getInvoice(id: Long): Invoice? = Invoice.findById(id)

    /**
     * `sourceDocumentId` is a plain, non-unique index on purpose — one scanned PDF may legitimately
     * carry several invoices — so the ordering is what makes this deterministic, not uniqueness.
     */
    override fun getInvoiceBySourceDocument(documentId: Long): Invoice? =
        Invoice
            .find { InvoiceTable.sourceDocumentId eq documentId }
            .orderBy(InvoiceTable.id to SortOrder.ASC)
            .firstOrNull()

    override fun getInvoicesBySourceDocuments(documentIds: List<Long>): Map<Long, Invoice> {
        if (documentIds.isEmpty()) return emptyMap()

        val byDocument = LinkedHashMap<Long, Invoice>()
        InvoiceTable
            .selectAll()
            .where { InvoiceTable.sourceDocumentId inList documentIds }
            .orderBy(InvoiceTable.id to SortOrder.ASC)
            .forEach { row ->
                // Read the FK off the row rather than through `Invoice.sourceDocument`: touching that
                // reference would load the Document entity once per invoice, reintroducing exactly
                // the per-row query this method exists to avoid.
                val documentId = row[InvoiceTable.sourceDocumentId]?.value ?: return@forEach
                if (!byDocument.containsKey(documentId)) {
                    byDocument[documentId] = Invoice.wrapRow(row)
                }
            }
        return byDocument
    }

    override fun getAllocationCountsByDocuments(documentIds: List<Long>): Map<Long, Int> {
        if (documentIds.isEmpty()) return emptyMap()
        val counts = HashMap<Long, Int>()
        val transactions = PaymentAllocationTable.bankTransactionId.countDistinct()

        val invoiceIdByDocument = getInvoicesBySourceDocuments(documentIds).mapValues { it.value.id.value }
        if (invoiceIdByDocument.isNotEmpty()) {
            val byInvoice =
                PaymentAllocationTable
                    .select(PaymentAllocationTable.invoiceId, transactions)
                    .where {
                        (PaymentAllocationTable.invoiceId inList invoiceIdByDocument.values.toList()) and
                            PaymentAllocationTable.bankTransactionId.isNotNull()
                    }.groupBy(PaymentAllocationTable.invoiceId)
                    .associate { (it[PaymentAllocationTable.invoiceId]?.value ?: 0L) to it[transactions].toInt() }
            invoiceIdByDocument.forEach { (documentId, invoiceId) -> byInvoice[invoiceId]?.let { counts[documentId] = it } }
        }

        val salesIdByDocument = HashMap<Long, Long>()
        SalesInvoiceTable
            .select(SalesInvoiceTable.id, SalesInvoiceTable.documentId)
            .where { SalesInvoiceTable.documentId inList documentIds }
            .forEach { row ->
                val documentId = row[SalesInvoiceTable.documentId]?.value ?: return@forEach
                salesIdByDocument.putIfAbsent(documentId, row[SalesInvoiceTable.id].value)
            }
        if (salesIdByDocument.isNotEmpty()) {
            val bySales =
                PaymentAllocationTable
                    .select(PaymentAllocationTable.salesInvoiceId, transactions)
                    .where {
                        (PaymentAllocationTable.salesInvoiceId inList salesIdByDocument.values.toList()) and
                            PaymentAllocationTable.bankTransactionId.isNotNull()
                    }.groupBy(PaymentAllocationTable.salesInvoiceId)
                    .associate { (it[PaymentAllocationTable.salesInvoiceId]?.value ?: 0L) to it[transactions].toInt() }
            salesIdByDocument.forEach { (documentId, salesId) -> bySales[salesId]?.let { counts[documentId] = (counts[documentId] ?: 0) + it } }
        }
        return counts
    }

    // ===== RECHNUNGEN LIST (the union of two populations) =====

    override fun countUncapturedInvoiceDocuments(): Long {
        flushPendingInvoices()
        return Document.find { uncapturedPredicate() }.count()
    }

    override fun findUncapturedInvoiceDocuments(
        limit: Int,
        offset: Long,
    ): List<Document> {
        flushPendingInvoices()
        return Document
            .find { uncapturedPredicate() }
            .orderBy(DocumentTable.uploadedAt to SortOrder.DESC, DocumentTable.id to SortOrder.DESC)
            .limit(limit)
            .offset(offset)
            .toList()
    }

    /**
     * The pending-insert flush Exposed will not do on its own.
     *
     * `EntityLifecycleInterceptor` flushes only the entities of the tables in a query's FROM clause,
     * and this query names `documents` there — `invoice` appears only inside the correlated
     * subquery. Without this, an invoice created earlier in the same transaction would still be
     * sitting in the entity cache and its document would be reported as uncaptured work.
     */
    private fun flushPendingInvoices() {
        TransactionManager.current().entityCache.flush(listOf(InvoiceTable))
    }

    /**
     * `INVOICE`-type, still active, and with no invoice extracted from it yet.
     *
     * `notExists` rather than a left join: `source_document_id` is deliberately non-unique — one
     * scanned PDF may hold several invoices — so a join would return the document once per invoice.
     */
    private fun uncapturedPredicate(): Op<Boolean> =
        (DocumentTable.fileType eq "INVOICE") and
            (DocumentTable.isActive eq true) and
            notExists(
                InvoiceTable.select(InvoiceTable.id).where { InvoiceTable.sourceDocumentId eq DocumentTable.id },
            )

    override fun countInvoicesByReviewState(reviewed: Boolean?): Long = Invoice.find { reviewPredicate(reviewed) }.count()

    override fun findInvoicesByReviewState(
        reviewed: Boolean?,
        limit: Int,
        offset: Long,
    ): List<Invoice> =
        Invoice
            .find { reviewPredicate(reviewed) }
            .orderBy(InvoiceTable.issueDate to SortOrder.DESC_NULLS_LAST, InvoiceTable.id to SortOrder.DESC)
            .limit(limit)
            .offset(offset)
            .toList()

    private fun reviewPredicate(reviewed: Boolean?): Op<Boolean> =
        when (reviewed) {
            null -> Op.TRUE
            true -> InvoiceTable.reviewedAt.isNotNull()
            false -> InvoiceTable.reviewedAt.isNull()
        }

    override fun replaceVatBreakdown(
        invoiceId: Long,
        rows: List<DocumentRepository.VatBreakdownRow>,
    ) {
        val invoice = requireInvoice(invoiceId)

        InvoiceVatBreakdown
            .find { InvoiceVatBreakdownTable.invoiceId eq invoiceId }
            .toList()
            .forEach { it.delete() }

        rows.forEach { row ->
            InvoiceVatBreakdown.new {
                this.invoice = invoice
                this.vatCategoryCode = row.vatCategoryCode
                this.vatRate = row.vatRate
                this.taxableAmount = row.taxableAmount
                this.vatAmount = row.vatAmount
                this.exemptionReasonCode = row.exemptionReasonCode
                this.exemptionReasonText = row.exemptionReasonText
                this.description = row.description
            }
        }
        touchInvoice(invoice)
    }

    override fun replaceInvoiceLines(
        invoiceId: Long,
        rows: List<DocumentRepository.InvoiceLineRow>,
    ) {
        val invoice = requireInvoice(invoiceId)

        InvoiceLine
            .find { InvoiceLineTable.invoiceId eq invoiceId }
            .toList()
            .forEach { it.delete() }

        rows.forEach { row ->
            InvoiceLine.new {
                this.invoice = invoice
                this.lineId = row.lineId
                this.itemName = row.itemName
                this.itemDescription = row.itemDescription
                this.quantity = row.quantity
                this.unitCode = row.unitCode
                this.netUnitPrice = row.netUnitPrice
                this.lineNetAmount = row.lineNetAmount
                this.vatCategoryCode = row.vatCategoryCode
                this.vatRate = row.vatRate
                this.sellerItemId = row.sellerItemId
                this.buyerItemId = row.buyerItemId
                this.itemStandardId = row.itemStandardId
            }
        }
        touchInvoice(invoice)
    }

    override fun replaceAllowancesCharges(
        invoiceId: Long,
        rows: List<DocumentRepository.AllowanceChargeRow>,
    ) {
        val invoice = requireInvoice(invoiceId)

        InvoiceAllowanceCharge
            .find { InvoiceAllowanceChargeTable.invoiceId eq invoiceId }
            .toList()
            .forEach { it.delete() }

        rows.forEach { row ->
            InvoiceAllowanceCharge.new {
                this.invoice = invoice
                this.isCharge = row.isCharge
                this.amount = row.amount
                this.baseAmount = row.baseAmount
                this.percentage = row.percentage
                this.reasonCode = row.reasonCode
                this.reasonText = row.reasonText
                this.vatCategoryCode = row.vatCategoryCode
                this.vatRate = row.vatRate
            }
        }
        touchInvoice(invoice)
    }

    /**
     * Creates the booking row if absent, updates it if present.
     *
     * Touches **only** [InvoiceBookingTable] — supplier-asserted content on [InvoiceTable] and its
     * child rows is unreachable from here by construction.
     *
     * A null argument means "leave unchanged": it is never written, so a partial edit cannot wipe a
     * field the caller did not mention, and on create the column keeps its default.
     */
    override fun upsertInvoiceBooking(
        invoiceId: Long,
        accountNo: String?,
        bpAccountNo: String?,
        buCode: String?,
        bookingText: String?,
        costCategoryId: String?,
        costCategoryId2: String?,
        costAmount: BigDecimal?,
        typeOfReceivable: String?,
        paymentDunningBlock: Boolean?,
        paidAt: LocalDateTime?,
        clearFields: Set<ClearableBookingField>,
    ): InvoiceBooking {
        val invoice = requireInvoice(invoiceId)

        val booking =
            InvoiceBooking
                .find { InvoiceBookingTable.invoiceId eq invoiceId }
                .firstOrNull()
                ?: InvoiceBooking.new { this.invoice = invoice }

        accountNo?.let { booking.accountNo = it }
        bpAccountNo?.let { booking.bpAccountNo = it }
        buCode?.let { booking.buCode = it }
        bookingText?.let { booking.bookingText = it }
        costCategoryId?.let { booking.costCategoryId = it }
        costCategoryId2?.let { booking.costCategoryId2 = it }
        costAmount?.let { booking.costAmount = it }
        typeOfReceivable?.let { booking.typeOfReceivable = it }
        paymentDunningBlock?.let { booking.paymentDunningBlock = it }
        paidAt?.let { booking.paidAt = it }

        clearBookingFields(booking, clearFields)

        return booking
    }

    /**
     * Nulls the named columns. Applied after the value arguments so the outcome of a contradictory
     * request is deterministic; the service rejects that combination before it gets here.
     *
     * An exhaustive `when` on purpose: adding a [ClearableBookingField] constant without handling
     * it becomes a compile error rather than a field that silently refuses to clear.
     */
    private fun clearBookingFields(
        booking: InvoiceBooking,
        clearFields: Set<ClearableBookingField>,
    ) {
        for (field in clearFields) {
            when (field) {
                ClearableBookingField.ACCOUNT_NO -> booking.accountNo = null
                ClearableBookingField.BP_ACCOUNT_NO -> booking.bpAccountNo = null
                ClearableBookingField.BU_CODE -> booking.buCode = null
                ClearableBookingField.BOOKING_TEXT -> booking.bookingText = null
                ClearableBookingField.COST_CATEGORY_ID -> booking.costCategoryId = null
                ClearableBookingField.COST_CATEGORY_ID_2 -> booking.costCategoryId2 = null
                ClearableBookingField.COST_AMOUNT -> booking.costAmount = null
                ClearableBookingField.TYPE_OF_RECEIVABLE -> booking.typeOfReceivable = null
                ClearableBookingField.PAID_AT -> booking.paidAt = null
            }
        }
    }

    private fun requireInvoice(invoiceId: Long): Invoice =
        Invoice.findById(invoiceId)
            ?: throw NoSuchElementException("Invoice not found: $invoiceId")

    /** Child rows are part of the invoice, so replacing them is a change to the invoice. */
    private fun touchInvoice(invoice: Invoice) {
        invoice.updatedAt = currentTimestamp()
    }

    // ===== BANK STATEMENT AGGREGATE (normalized model) =====

    override fun createBankStatement(
        sourceDocumentId: Long?,
        format: String,
        accountIban: String?,
        accountBic: String?,
        bankName: String?,
        accountNumber: String?,
        statementNumber: String?,
        legalSequenceNumber: String?,
        statementDate: LocalDateTime?,
        periodStart: LocalDateTime?,
        periodEnd: LocalDateTime?,
        openingBalance: BigDecimal?,
        closingBalance: BigDecimal?,
        currency: String,
    ): BankStatement {
        val source =
            sourceDocumentId?.let {
                Document.findById(it) ?: throw NoSuchElementException("Document not found: $it")
            }

        return BankStatement.new {
            this.sourceDocument = source
            this.format = format
            this.accountIban = accountIban
            this.accountBic = accountBic
            this.bankName = bankName
            this.accountNumber = accountNumber
            this.statementNumber = statementNumber
            this.legalSequenceNumber = legalSequenceNumber
            this.statementDate = statementDate
            this.periodStart = periodStart
            this.periodEnd = periodEnd
            this.openingBalance = openingBalance
            this.closingBalance = closingBalance
            this.currency = currency
            this.createdAt = currentTimestamp()
            this.updatedAt = null
        }
    }

    override fun getBankStatement(id: Long): BankStatement? = BankStatement.findById(id)

    /**
     * `sourceDocumentId` is a plain, non-unique index on purpose — one upload may legitimately hold
     * several statements — so the ordering is what makes this deterministic, not uniqueness.
     */
    override fun getBankStatementBySourceDocument(documentId: Long): BankStatement? =
        BankStatement
            .find { BankStatementTable.sourceDocumentId eq documentId }
            .orderBy(BankStatementTable.id to SortOrder.ASC)
            .firstOrNull()

    override fun getBankStatementsBySourceDocuments(documentIds: List<Long>): Map<Long, BankStatement> {
        if (documentIds.isEmpty()) return emptyMap()

        val byDocument = LinkedHashMap<Long, BankStatement>()
        BankStatementTable
            .selectAll()
            .where { BankStatementTable.sourceDocumentId inList documentIds }
            .orderBy(BankStatementTable.id to SortOrder.ASC)
            .forEach { row ->
                // Read the FK off the row rather than through `BankStatement.sourceDocument`: touching
                // that reference would load the Document entity once per statement, reintroducing
                // exactly the per-row query this method exists to avoid.
                val documentId = row[BankStatementTable.sourceDocumentId]?.value ?: return@forEach
                if (!byDocument.containsKey(documentId)) {
                    byDocument[documentId] = BankStatement.wrapRow(row)
                }
            }
        return byDocument
    }

    override fun replaceTransactions(
        statementId: Long,
        rows: List<DocumentRepository.BankTransactionRow>,
    ) {
        val statement = requireBankStatement(statementId)

        BankStatementTransaction
            .find { BankStatementTransactionTable.statementId eq statementId }
            .toList()
            .forEach { it.delete() }

        rows.forEach { row ->
            BankStatementTransaction.new {
                this.statement = statement
                this.bookingDate = row.bookingDate
                this.valueDate = row.valueDate
                this.amount = row.amount
                this.currency = row.currency
                this.creditDebit = row.creditDebit
                this.status = row.status
                this.bankTxDomain = row.bankTxDomain
                this.bankTxFamily = row.bankTxFamily
                this.bankTxSubFamily = row.bankTxSubFamily
                this.accountServicerReference = row.accountServicerReference
                this.endToEndId = row.endToEndId
                this.mandateId = row.mandateId
                this.creditorId = row.creditorId
                this.counterpartyName = row.counterpartyName
                this.counterpartyIban = row.counterpartyIban
                this.counterpartyBic = row.counterpartyBic
                this.remittanceUnstructured = row.remittanceUnstructured
                this.remittanceStructuredRef = row.remittanceStructuredRef
                this.reversalIndicator = row.reversalIndicator
                this.rawEntry = row.rawEntry
            }
        }
        touchBankStatement(statement)
    }

    override fun getTransactions(statementId: Long): List<BankStatementTransaction> =
        BankStatementTransaction
            .find { BankStatementTransactionTable.statementId eq statementId }
            .orderBy(BankStatementTransactionTable.id to SortOrder.ASC)
            .toList()

    override fun findStatementsForAccount(
        accountIban: String?,
        accountNumber: String?,
        excludeStatementId: Long,
    ): List<BankStatement> {
        // With no account identity there is nothing to compare against, so claiming any overlap
        // would be a guess.
        val account = accountIban ?: accountNumber ?: return emptyList()

        val accountMatch =
            if (accountIban != null) {
                BankStatementTable.accountIban eq account
            } else {
                BankStatementTable.accountNumber eq account
            }

        return BankStatement
            .find { accountMatch and (BankStatementTable.id neq excludeStatementId) }
            .orderBy(BankStatementTable.id to SortOrder.ASC)
            .toList()
    }

    override fun getTransactionDateRanges(statementIds: List<Long>): Map<Long, Pair<LocalDateTime, LocalDateTime>> {
        if (statementIds.isEmpty()) return emptyMap()

        val earliest = BankStatementTransactionTable.bookingDate.min()
        val latest = BankStatementTransactionTable.bookingDate.max()

        return BankStatementTransactionTable
            .select(BankStatementTransactionTable.statementId, earliest, latest)
            .where { BankStatementTransactionTable.statementId inList statementIds }
            .groupBy(BankStatementTransactionTable.statementId)
            .mapNotNull { row ->
                val from = row[earliest] ?: return@mapNotNull null
                val to = row[latest] ?: return@mapNotNull null
                row[BankStatementTransactionTable.statementId].value to (from to to)
            }.toMap()
    }

    override fun getTransactionsForStatements(statementIds: List<Long>): List<BankStatementTransaction> {
        if (statementIds.isEmpty()) return emptyList()
        return BankStatementTransaction
            .find { BankStatementTransactionTable.statementId inList statementIds }
            .orderBy(BankStatementTransactionTable.id to SortOrder.ASC)
            .toList()
    }

    override fun markTransactionDuplicate(
        transactionId: Long,
        duplicateOfId: Long?,
    ) {
        BankStatementTransactionTable.update({ BankStatementTransactionTable.id eq transactionId }) {
            it[duplicateOfTransactionId] = duplicateOfId?.let { id -> EntityID(id, BankStatementTransactionTable) }
        }
    }

    override fun markStatementOverlap(
        statementId: Long,
        overlappingId: Long?,
    ) {
        BankStatementTable.update({ BankStatementTable.id eq statementId }) {
            it[overlappingStatementId] = overlappingId?.let { id -> EntityID(id, BankStatementTable) }
        }
    }

    private fun requireBankStatement(statementId: Long): BankStatement =
        BankStatement.findById(statementId)
            ?: throw NoSuchElementException("Bank statement not found: $statementId")

    /** Entries are part of the statement, so replacing them is a change to the statement. */
    private fun touchBankStatement(statement: BankStatement) {
        statement.updatedAt = currentTimestamp()
    }
}
