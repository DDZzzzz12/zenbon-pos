/**
 * Document Entity - Exposed DAO
 *
 * Domain model representing a document in the system.
 * Maps to the documents table and provides object-oriented
 * access to document metadata with lazy loading capabilities.
 *
 * Type-specific metadata is stored in separate entities:
 * - Invoice metadata: DocumentInvoiceMetadata (vendor, amounts, tax, etc.)
 * - Bank statement metadata: DocumentBankStatementMetadata (bank, balances, period, etc.)
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.document.domain.models

import kotlinx.datetime.number
import org.example.project.core.domain.enums.document.DocumentType
import org.example.project.features.document.database.tables.DocumentBankStatementMetadataTable
import org.example.project.features.document.database.tables.DocumentInvoiceMetadataTable
import org.example.project.features.document.database.tables.DocumentLinkTable
import org.example.project.features.document.database.tables.DocumentTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.dao.*
import org.jetbrains.exposed.v1.jdbc.SizedIterable

/**
 * Exposed DAO entity for documents.
 *
 * Provides object-oriented access to document metadata including
 * file paths, type classification, audit tracking, and content hashing.
 *
 * Type-specific metadata is stored separately:
 * - Invoice metadata for INVOICE documents (vendor, amounts, tax breakdown)
 * - Bank statement metadata for BANK_STATEMENT documents (bank, balances, period)
 */
class Document(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<Document>(DocumentTable)

    /** Original filename as uploaded or generated */
    var filename by DocumentTable.filename

    /** Type of document (INVOICE, BANK_STATEMENT, CONTRACTS, CUSTOM) */
    var fileType by DocumentTable.fileType

    /** Full path to file on disk */
    var filePath by DocumentTable.filePath

    /** File size in bytes */
    var fileSize by DocumentTable.fileSize

    /** Timestamp when document was uploaded or generated */
    var uploadedAt by DocumentTable.uploadedAt

    /** Timestamp when document metadata was last modified - null if never modified */
    var updatedAt by DocumentTable.updatedAt

    /** Employee who uploaded or generated the document */
    var uploadedBy by Employee referencedOn DocumentTable.uploadedBy

    /** JSON metadata for AI-extracted keywords (future feature) */
    var metadataJson by DocumentTable.metadataJson

    /** Soft delete flag - false means document is deleted but record remains for audit */
    var isActive by DocumentTable.isActive

    /** SHA-256 hash of file content for deduplication and integrity verification (required) */
    var contentHash by DocumentTable.contentHash

    /** Raw OCR text backing full-text search; supplied by the client-side document scanner on upload */
    var ocrText by DocumentTable.ocrText

    /** MIME type of the document (e.g., "application/pdf", "image/jpeg", "image/png") */
    var mediaType by DocumentTable.mediaType

    /**
     * The document's workflow state, one of `ProcessingStatus`'s constant names.
     *
     * Set on upload from the e-invoice parse outcome: `Success` → `COMPLETED` (only once the invoice
     * is actually stored), `NotConformant` → `UNPROCESSABLE`, `Failure` → `FAILED`, while
     * `NoEInvoiceFound` writes nothing and leaves the `NONE` default — the normal scanned-invoice
     * case. Later values come from human action (`IN_PROGRESS`, `REVIEWED`).
     *
     * Write it through `DocumentRepository.updateProcessingStatus`, which stores the constant name:
     * `DocumentMapper` reads it back with a bare `valueOf` and no fallback.
     *
     * `PENDING`/`PROCESSING` are vestigial queue states from the removed asynchronous pipeline.
     */
    var processingStatus by DocumentTable.processingStatus

    /** Number of processing retry attempts */
    var processingRetryCount by DocumentTable.processingRetryCount

    /** Error message from last failed processing attempt */
    var processingError by DocumentTable.processingError

    /** Epoch millis when processing started — used by watchdog to detect stuck documents */
    var processingStartedAt by DocumentTable.processingStartedAt

    // ===== TYPE-SPECIFIC METADATA (Separate Tables) =====

    /**
     * Invoice metadata associated with this document.
     * Only applicable for INVOICE type documents.
     * Returns null for other document types.
     */
    val invoiceMetadata by DocumentInvoiceMetadata optionalBackReferencedOn DocumentInvoiceMetadataTable.documentId

    /**
     * Bank statement metadata associated with this document.
     * Only applicable for BANK_STATEMENT type documents.
     * Returns null for other document types.
     */
    val bankStatementMetadata by DocumentBankStatementMetadata optionalBackReferencedOn DocumentBankStatementMetadataTable.documentId

    // ===== DOCUMENT LINKS =====

    /**
     * Links where this document is the linked document.
     * Returns all DocumentLink records where this Document is referenced as the document.
     */
    val documentLinks by DocumentLink referrersOn DocumentLinkTable.documentId

    /**
     * Links where this document is the bank statement.
     * Returns all DocumentLink records where this Document is referenced as the bank statement.
     */
    val bankStatementLinks by DocumentLink referrersOn DocumentLinkTable.bankStatementDocumentId

    /**
     * The Kassenbuch entries linking this document (at most one: `linked_document_id` is unique).
     *
     * A query rather than `optionalReferrersOn`, because `linked_document_id` is a plain id column
     * now (the foreign key is host-declared, see `CrossAreaForeignKeys`).
     */
    val kassenbuchLinks: SizedIterable<KassenbuchHeader>
        get() = KassenbuchHeader.find { KassenbuchHeaderTable.linkedDocumentId eq id.value }

    /**
     * Checks if this document is a formal invoice.
     */
    fun isInvoice(): Boolean = fileType == DocumentType.INVOICE.name

    /**
     * Checks if this document is a bank statement.
     */
    fun isBankStatement(): Boolean = fileType == DocumentType.BANK_STATEMENT.name

    /**
     * Checks if this document type supports invoice metadata.
     */
    fun supportsInvoiceMetadata(): Boolean = fileType == DocumentType.INVOICE.name

    /**
     * Checks if this document type supports bank statement metadata.
     */
    fun supportsBankStatementMetadata(): Boolean = fileType == DocumentType.BANK_STATEMENT.name

    /**
     * Checks if this document has any links (as invoice or bank statement).
     *
     * @return true if document has at least one link
     */
    fun hasLinks(): Boolean = documentLinks.count() > 0 || bankStatementLinks.count() > 0

    /**
     * Gets total number of links for this document.
     *
     * @return Combined count of invoice links and bank statement links
     */
    fun getLinkCount(): Int = documentLinks.count().toInt() + bankStatementLinks.count().toInt()

    /**
     * Gets the month folder name from the uploadedAt timestamp.
     * Returns format: YYYY-MM (e.g., "2025-01")
     */
    fun getMonthFolder(): String {
        val year = uploadedAt.year
        val month =
            uploadedAt.month.number
                .toString()
                .padStart(2, '0')
        return "$year-$month"
    }

    /**
     * Gets the type folder name based on the document type.
     *
     * @return Folder name for organizing files (e.g., "RECHNUNG", "KONTO-AUSZUEGE")
     */
    fun getTypeFolder(): String {
        val enumType = DocumentType.fromStringOrNull(fileType)
        return enumType?.folderName ?: fileType.uppercase()
    }

    /**
     * Formats file size to human-readable format.
     * Examples: "45.6 KB", "1.2 MB", "342 B"
     */
    fun formatFileSize(): String =
        when {
            fileSize < 1024 -> "$fileSize B"
            fileSize < 1024 * 1024 -> "%.1f KB".format(fileSize / 1024.0)
            else -> "%.1f MB".format(fileSize / (1024.0 * 1024.0))
        }

    override fun toString(): String = "Document(id=$id, filename='$filename', type=$fileType, size=${formatFileSize()}, uploadedAt=$uploadedAt)"
}
