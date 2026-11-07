/**
 * Service interface for print job history operations.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors;
 * the global StatusPages handler converts to application/problem+json.
 * Migrated from Result<T, Error> in Stage B Batch 5.
 */
package org.example.project.features.printer.domain.service

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.printer.PrinterDto

/**
 * Service for print job history business operations.
 *
 * Handles retrieval and filtering of print job audit trail records
 * with proper transaction management and DTO conversion.
 */
interface PrintJobHistoryService {
    /**
     * Get print jobs filtered by date range with optional filters.
     *
     * Retrieves print jobs within a specified date range for the side panel display.
     * Supports filtering by status, print type, and printer location.
     *
     * @param startDate Start of date range (inclusive)
     * @param endDate End of date range (inclusive)
     * @param limit Maximum number of records to return (default: 50)
     * @param status Optional filter by print job status
     * @param printType Optional filter by print type
     * @param locationId Optional filter by printer location ID
     * @return List of print job history DTOs
     */
    suspend fun getPrintJobsByDateRange(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        limit: Int = 50,
        status: PrinterDto.PrintJobStatus? = null,
        printType: PrinterDto.PrintType? = null,
        locationId: Long? = null,
    ): List<PrinterDto.PrintJobHistoryDto>
}
