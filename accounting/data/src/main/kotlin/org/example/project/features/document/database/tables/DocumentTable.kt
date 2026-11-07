/**
 * Document Table Definition
 *
 * Database table for storing document metadata with organized file storage structure.
 * Tracks all documents in the system including invoices, bank statements, and custom documents.
 *
 * Storage Architecture:
 * - Files stored on disk: {root}/{type}/{YYYY-MM}/filename.ext
 * - Metadata stored in PostgreSQL for efficient querying
 * - SHA-256 hashing for deduplication and integrity verification
 * - Soft delete pattern for audit trail preservation
 *
 * Type-specific metadata is stored in separate tables:
 * - Invoice metadata: DocumentInvoiceMetadataTable (vendor, amounts, tax, etc.)
 * - Bank statement metadata: DocumentBankStatementMetadataTable (bank, balances, period, etc.)
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.document.database.tables

import org.example.project.features.employee.database.tables.EmployeeTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Exposed table definition for documents.
 *
 * Comprehensive metadata tracking with performance-optimized indexes
 * for common query patterns (filtering by type, date, uploader).
 */
object DocumentTable : LongIdTable("documents", "id") {
    /** Original filename as uploaded/generated */
    val filename = varchar("filename", 255)

    /** Document type stored as string (INVOICE, BANK_STATEMENT, CONTRACTS, or custom type name) */
    val fileType = varchar("file_type", 50)

    /** Full path to file on disk (unique constraint) */
    val filePath = varchar("file_path", 512)

    /** File size in bytes for storage management */
    val fileSize = long("file_size")

    /** Upload or generation timestamp */
    val uploadedAt = datetime("uploaded_at")

    /** Last modification timestamp - null if never modified after creation */
    val updatedAt = datetime("updated_at").nullable()

    /** Employee number who uploaded/generated the document */
    val uploadedBy = reference("uploaded_by", EmployeeTable)

    /** JSON metadata for AI-extracted keywords (future feature preparation) */
    val metadataJson = text("metadata_json").nullable()

    /** Soft delete flag - false means document is deleted but record remains for audit */
    val isActive = bool("is_active").default(true)

    /** SHA-256 hash of file content for deduplication and integrity verification (required) */
    val contentHash = varchar("content_hash", 64)

    /** Raw OCR text backing full-text search; supplied by the client-side document scanner on upload */
    val ocrText = text("ocr_text").nullable()

    /** MIME type of the document (e.g., "application/pdf", "image/jpeg", "image/png") */
    val mediaType = varchar("media_type", 50).default("application/pdf")

    /**
     * The document's workflow state, one of `ProcessingStatus`'s constant names.
     *
     * Set on upload from the e-invoice parse outcome: `Success` → `COMPLETED` (only once the invoice
     * is actually stored), `NotConformant` → `UNPROCESSABLE`, `Failure` → `FAILED`, while
     * `NoEInvoiceFound` writes nothing and leaves the `NONE` default — the normal scanned-invoice
     * case. Later values come from human action (`IN_PROGRESS`, `REVIEWED`).
     *
     * `PENDING`/`PROCESSING` are vestigial queue states from the removed asynchronous pipeline.
     */
    val processingStatus = varchar("processing_status", 20).default("NONE")

    /** Number of processing retry attempts */
    val processingRetryCount = integer("processing_retry_count").default(0)

    /** Error message from last failed processing attempt */
    val processingError = text("processing_error").nullable()

    /** Epoch millis when processing started — used by watchdog to detect stuck documents */
    val processingStartedAt = long("processing_started_at").nullable()

    // NOTE: Type-specific metadata (invoice details, bank statement details) is stored in
    // separate tables: DocumentInvoiceMetadataTable and DocumentBankStatementMetadataTable.

    init {
        // Performance indexes for common query patterns

        // Single-column indexes for filtering
        index(false, fileType) // Filter by type (invoices, bank statements, contracts, custom)
        index(false, uploadedAt) // Date-based queries
        index(false, isActive) // Active vs deleted documents
        index(false, uploadedBy) // Documents by employee
        index(false, contentHash) // Deduplication checks
        index(false, mediaType) // Filter by media type

        // Composite index for common combined filters (type + date range)
        index(false, fileType, uploadedAt)

        // The adminApp work queue: status bucket + the uploadedAt DESC ordering search() applies.
        // Mirrors Flyway V87 — see that migration's comment for why it is composite.
        index("idx_documents_processing_status_uploaded_at", false, processingStatus, uploadedAt)

        // Unique constraint to prevent duplicate file paths
        uniqueIndex(filePath)

        // NOTE: Trigram GIN index on ocrText is created via Flyway migration V24
        // (not possible via Exposed DSL)
    }
}
