/**
 * Database table for print job history and audit trail.
 *
 * Tracks all print operations for compliance and troubleshooting.
 * Every print attempt (successful or failed) is recorded for audit purposes.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.printer.database.tables

import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.domain.enums.setup.PrinterType
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Print job history table definition.
 *
 * Records comprehensive audit trail of all print operations including:
 * - Document type and ID
 * - Printer type used
 * - Success/failure status
 * - Error messages for failed prints
 * - Timestamp and employee information
 */
object PrintJobHistoryTable : LongIdTable("print_job_history", "id") {
    /**
     * Type of document that was printed (BILL, ORDER, ZBON).
     */
    val printType = enumerationByName("print_type", 20, PrinterDto.PrintType::class)

    /**
     * Type of printer used (USB, NETWORK).
     */
    val printerType = enumerationByName("printer_type", 20, PrinterType::class)

    /**
     * ID of the document that was printed.
     * References Bill, Order, or ZBon table depending on printType.
     */
    val documentId = varchar("document_id", 100)

    /**
     * Status of the print job (SUCCESS, FAILED, PENDING).
     */
    val status = enumerationByName("status", 20, PrinterDto.PrintJobStatus::class)

    /**
     * Error message if print failed (null if successful).
     * Contains detailed error information for troubleshooting.
     */
    val errorMessage = text("error_message").nullable()

    /**
     * Timestamp when print was executed.
     * Uses kotlinx.datetime.LocalDateTime for better filtering and sorting.
     */
    val printedAt = datetime("printed_at")

    /**
     * Employee number who initiated the print operation.
     */
    val printedBy = varchar("printed_by", 100)

    /**
     * Reference to the printer location where this print was executed.
     * Links print jobs to specific locations (Kitchen, Bar, etc.) for tracking.
     * Nullable because older records may not have location data.
     */
    val printerLocationId = reference("printer_location_id", PrinterLocationTable).nullable()

    init {
        // Create index on document_id for fast lookup by document
        index(false, documentId)

        // Create index on printedAt for date range queries
        index(false, printedAt)

        // Create index on status for filtering successful/failed prints
        index(false, status)

        // Create index on printerLocationId for filtering by location
        index(false, printerLocationId)
    }
}
