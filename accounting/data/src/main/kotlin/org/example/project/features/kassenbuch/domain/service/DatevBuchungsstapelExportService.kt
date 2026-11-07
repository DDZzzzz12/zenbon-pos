package org.example.project.features.kassenbuch.domain.service

import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.domain.enums.setting.DatevExportSource
import java.util.UUID

/**
 * Service for the DATEV Buchungsstapel (EXTF_Buchungsstapel.csv) export.
 *
 * Targets DATEV Rechnungswesen via Stapelverarbeitung — 125-column CSV,
 * UTF-8, CRLF, per-line-item granularity. See
 * `docs/superpowers/specs/2026-04-21-datev-buchungsstapel-export-design.md`.
 *
 * Inherits the `export()` contract from [DatevFileExportService] so route
 * handlers can emit the download uniformly across DATEV formats.
 */
interface DatevBuchungsstapelExportService : DatevFileExportService {
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
}
