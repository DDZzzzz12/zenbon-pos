package org.example.project.features.kassenbuch.domain.service

import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.domain.enums.setting.DatevExportSource
import java.util.UUID

/**
 * DATEV Buchungsstapel export bundled with digital Belege in one Belegtransfer ZIP
 * (integrated Beleglink, BEDI / Unternehmen online). Kassenbuch-only.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on error.
 */
interface DatevBuchungsstapelBelegeExportService {
    /**
     * Generate the ZIP without persisting to disk (records an export-log entry).
     *
     * @param cashBookIds When non-null, restricts the export to these cash books; `null` (the
     *        default) exports ALL of the shop's cash books — today's behaviour.
     */
    suspend fun exportTransient(
        startDate: String,
        endDate: String,
        exportedByUserId: UUID,
        cashBookIds: List<Long>? = null,
    ): TransientExportResult

    /**
     * Generate + persist to the export path + record an export-log entry.
     *
     * @param cashBookIds When non-null, restricts the export to these cash books; `null` (the
     *        default) exports ALL of the shop's cash books — today's behaviour.
     */
    suspend fun export(
        startDate: String,
        endDate: String,
        exportedByUserId: UUID,
        periodLabel: String = "",
        source: DatevExportSource = DatevExportSource.MANUAL,
        cashBookIds: List<Long>? = null,
    ): DatevExportLogDto
}
