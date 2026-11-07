/**
 * Repository interface for print job history operations.
 *
 * Provides database operations for tracking and querying print job audit trail.
 * Records all print attempts for compliance and troubleshooting purposes.
 * Returns plain entities; exceptions propagate to the service layer.
 */
package org.example.project.features.printer.domain.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.domain.enums.setup.PrinterType
import org.example.project.features.printer.domain.models.PrintJobHistory

/**
 * Repository for print job history operations.
 *
 * Handles database access for PrintJobHistory entities including
 * creation of audit records and retrieval by document ID.
 */
interface PrintJobHistoryRepository {
    /**
     * Create a new print job history record.
     *
     * @param printType Type of document printed (BILL, ORDER, ZBON)
     * @param printerType Type of printer used (USB, NETWORK)
     * @param documentId ID of the document that was printed
     * @param status Status of the print job (SUCCESS, FAILED, PENDING)
     * @param errorMessage Error details if print failed (null if successful)
     * @param printedBy Employee number who initiated the print
     * @param printerLocationId ID of printer location where print was executed (nullable)
     * @return The ID of the created print job record
     */
    fun createPrintJob(
        printType: PrinterDto.PrintType,
        printerType: PrinterType,
        documentId: String,
        status: PrinterDto.PrintJobStatus,
        errorMessage: String?,
        printedBy: String,
        printerLocationId: Long? = null,
    ): Long

    /**
     * Get all print jobs for a specific document.
     *
     * @param documentId ID of the document to query
     * @return List of print job records (empty if none found)
     */
    fun getPrintJobsByDocumentId(documentId: String): List<PrintJobHistory>

    /**
     * Get print jobs filtered by date range.
     *
     * @param startDate Start of date range (inclusive)
     * @param endDate End of date range (inclusive)
     * @param limit Maximum number of records to return (default: 50)
     * @return List of print job records
     */
    fun getPrintJobsByDateRange(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        limit: Int = 50,
    ): List<PrintJobHistory>

    /**
     * Get print jobs for a specific printer location.
     *
     * @param printerLocationId ID of the printer location
     * @param startDate Start of date range (inclusive)
     * @param endDate End of date range (inclusive)
     * @param limit Maximum number of records to return (default: 50)
     * @return List of print job records
     */
    fun getPrintJobsByLocation(
        printerLocationId: Long,
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        limit: Int = 50,
    ): List<PrintJobHistory>

    /**
     * Get recent print jobs with optional filters.
     *
     * @param limit Maximum number of records to return (default: 50)
     * @param status Optional filter by print job status
     * @param printType Optional filter by print type
     * @return List of print job records
     */
    fun getRecentPrintJobs(
        limit: Int = 50,
        status: PrinterDto.PrintJobStatus? = null,
        printType: PrinterDto.PrintType? = null,
    ): List<PrintJobHistory>
}
