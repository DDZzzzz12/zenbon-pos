package org.example.project.features.kassenbuch.domain.service

import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.domain.dto.export.DatevExportStatisticsDto
import org.example.project.core.domain.enums.setting.DatevExportSource
import java.util.UUID

/**
 * Service for the DATEV XML Kassenbuch Schnittstelle export.
 *
 * Extends [DatevFileExportService] for the unified `export()` contract that
 * produces the ZIP package (document.xml + per-day ledger XMLs + linked PDFs).
 * Keeps its own `getStatistics()` method with the Kassenbuch-oriented stats
 * shape — not part of [DatevFileExportService] because AP exports need a
 * different stats DTO.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors.
 */
interface DatevXmlExportService : DatevFileExportService {
    /**
     * Cash-book-scoped overload of [DatevFileExportService.export]. A sibling of the inherited
     * signature (not an override — the extra trailing parameter changes the arity), the same
     * pattern [DatevApExportService] uses for `excludeKassenbuchLinked`. Kotlin resolves a call
     * that omits [cashBookIds] to the inherited 5-parameter method, so no existing call site is
     * affected.
     *
     * @param cashBookIds When non-null, restricts the export to these cash books. `null` (the
     *        default) exports ALL of the shop's cash books — today's behaviour.
     */
    suspend fun export(
        startDate: String,
        endDate: String,
        exportedByUserId: UUID,
        periodLabel: String = "",
        source: DatevExportSource = DatevExportSource.AUTO,
        cashBookIds: List<Long>? = null,
    ): DatevExportLogDto

    /**
     * Cash-book-scoped overload of [DatevFileExportService.exportTransient]. See [export] for
     * why this is a sibling overload rather than an override.
     */
    suspend fun exportTransient(
        startDate: String,
        endDate: String,
        exportedByUserId: UUID,
        cashBookIds: List<Long>? = null,
    ): TransientExportResult

    /**
     * Kassenbuch-oriented pre-flight statistics for a date range.
     *
     * @param cashBookIds When non-null, restricts the statistics to these cash books; `null`
     *        (the default) covers ALL of the shop's cash books.
     */
    suspend fun getStatistics(
        startDate: String,
        endDate: String,
        cashBookIds: List<Long>? = null,
    ): DatevExportStatisticsDto
}
