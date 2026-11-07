package org.example.project.features.kassenbuch.domain.service

import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.domain.dto.datevxml.DatevApExportDto
import org.example.project.core.domain.enums.setting.DatevExportSource
import java.util.UUID

/**
 * Service for DATEV XML Eingangsrechnungen (Accounts Payable) export.
 *
 * Extends [DatevFileExportService] for the unified `export()` contract that
 * produces the ZIP package. Adds AP-specific overloads that carry the
 * `excludeKassenbuchLinked` flag (default true) — kept off the shared base
 * interface because the flag is meaningless for the Kassenbuch/Buchungsstapel
 * exports.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors.
 */
interface DatevApExportService : DatevFileExportService {
    /**
     * AP export to disk + log, with control over Kassenbuch-link exclusion.
     *
     * @param excludeKassenbuchLinked when true the export excludes
     *        Kassenbuch-linked invoices.
     */
    suspend fun export(
        startDate: String,
        endDate: String,
        exportedByUserId: UUID,
        periodLabel: String = "",
        source: DatevExportSource = DatevExportSource.AUTO,
        excludeKassenbuchLinked: Boolean,
    ): DatevExportLogDto

    /**
     * AP transient export (no disk write beyond the log entry), with control
     * over Kassenbuch-link exclusion.
     *
     * @param excludeKassenbuchLinked when true the export excludes
     *        Kassenbuch-linked invoices.
     */
    suspend fun exportTransient(
        startDate: String,
        endDate: String,
        exportedByUserId: UUID,
        excludeKassenbuchLinked: Boolean,
    ): TransientExportResult

    /**
     * AP-specific pre-flight statistics for a date range.
     *
     * @param excludeKassenbuchLinked when true (default) the counts reflect an
     *        export that excludes Kassenbuch-linked invoices, matching the
     *        export output for the same flag.
     */
    suspend fun getApExportStatistics(
        startDate: String,
        endDate: String,
        excludeKassenbuchLinked: Boolean = true,
    ): DatevApExportDto.ApExportStatisticsDto
}
