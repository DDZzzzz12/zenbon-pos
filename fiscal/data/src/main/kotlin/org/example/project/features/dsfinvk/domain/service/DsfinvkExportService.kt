/**
 * Service interface for DSFinV-K (Digitale Schnittstelle der Finanzverwaltung für Kassensysteme)
 * export operations.
 *
 * Provides business logic for German fiscal compliance CSV export including data validation,
 * file generation, and statistics reporting. Returns DTOs for API consumption and throws
 * [PosException] on error (Stage B PosError migration).
 *
 * DSFinV-K is required for Kassennachschau (fiscal audit) and IDEA audit software integration
 * in German POS systems.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.dsfinvk.domain.service

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto

/**
 * Service interface for DSFinV-K export operations.
 *
 * Handles business logic for German fiscal compliance CSV export including
 * validation, data aggregation, file generation, and statistics reporting.
 * Throws [PosException] on validation, not-found, or I/O failures.
 */
interface DsfinvkExportService {
    /**
     * Generates a complete DSFinV-K export for the specified date range.
     *
     * Creates all required CSV files according to DSFinV-K specification and
     * packages all files into a ZIP archive for download.
     *
     * @param request Export request with date range and options
     * @return ZIP archive bytes
     * @throws PosException with VALIDATION_FAILED for invalid date ranges,
     *         RESOURCE_NOT_FOUND when no data exists in range, or
     *         INTERNAL_ERROR for I/O / generation failures.
     */
    suspend fun generateExport(request: DsfinvkExportDto.ExportRequestDto): ByteArray

    /**
     * Validates export data before generating the export.
     *
     * Checks for data completeness and integrity issues including:
     * - Missing TSE signatures
     * - Unclosed Z-reports
     * - Missing VAT details
     * - Data consistency
     *
     * @param startDate Start date for validation (ISO 8601 format: yyyy-MM-dd)
     * @param endDate End date for validation (ISO 8601 format: yyyy-MM-dd)
     * @return Validation result DTO
     * @throws PosException with VALIDATION_FAILED for invalid date format or
     *         INTERNAL_ERROR for query failures.
     */
    suspend fun validateExportData(
        startDate: String,
        endDate: String,
    ): DsfinvkExportDto.ValidationResultDto

    /**
     * Retrieves export statistics for the specified date range.
     *
     * Provides summary counts and totals without generating the actual export,
     * useful for preview and reporting purposes.
     *
     * @param startDate Start date for statistics (ISO 8601 format: yyyy-MM-dd)
     * @param endDate End date for statistics (ISO 8601 format: yyyy-MM-dd)
     * @return Statistics DTO
     * @throws PosException with VALIDATION_FAILED for invalid date format or
     *         INTERNAL_ERROR for query failures.
     */
    suspend fun getExportStatistics(
        startDate: String,
        endDate: String,
    ): DsfinvkExportDto.ExportStatisticsDto
}
