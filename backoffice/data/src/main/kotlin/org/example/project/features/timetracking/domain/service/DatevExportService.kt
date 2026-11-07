package org.example.project.features.timetracking.domain.service

import org.example.project.core.domain.dto.timetracking.DatevExportDto

/**
 * Service interface for DATEV export operations (TimeTracking).
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors;
 * StatusPages catches it and emits application/problem+json.
 */
interface DatevExportService {
    suspend fun generatePreview(
        year: Int,
        month: Int,
    ): DatevExportDto.DatevPreviewDto

    suspend fun generateCsv(
        year: Int,
        month: Int,
    ): ByteArray

    suspend fun generatePdfTimesheet(
        employeeId: String,
        year: Int,
        month: Int,
    ): ByteArray
}
