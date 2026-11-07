package org.example.project.features.kassenbuch.domain.service

import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.domain.enums.setting.DatevExportSource
import java.util.UUID

/**
 * Common contract for all DATEV file exports produced by this system.
 *
 * Concrete implementations (XML Kassenbuch, XML AP, Kassenbuch-online CSV,
 * Buchungsstapel CSV) each produce a different format, but share this signature
 * so route handlers and accessors can treat them uniformly.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors.
 *
 * Pre-flight statistics are intentionally NOT part of this contract — each
 * export has a different statistics shape (the AP export, for example,
 * surfaces `missingMetadataCount` that Kassenbuch exports have no concept of).
 * Concrete services expose their own stats method with whatever DTO shape
 * makes sense for that format's UI.
 *
 * Named `DatevFileExportService` rather than `DatevExportService` to avoid a
 * package-qualified collision with the pre-existing
 * `org.example.project.features.timetracking.domain.service.DatevExportService`
 * (unrelated Stundenzettel export).
 */
interface DatevFileExportService {
    /**
     * Export data for the date range, persist an export log entry, and return the log DTO.
     *
     * @param startDate        Start date (inclusive), ISO "YYYY-MM-DD".
     * @param endDate          End date   (inclusive), ISO "YYYY-MM-DD".
     * @param exportedByUserId UUID of the authenticated employee initiating the export.
     * @param periodLabel      Human-readable period label (e.g. "2026-01"); used as
     *                         the sub-folder name under the configured export path.
     *                         Defaults to "" (caller may pass blank).
     * @param source           Whether this was produced by the AUTO scheduled job or a
     *                         MANUAL ad-hoc user action.  Defaults to [DatevExportSource.AUTO]
     *                         since the primary caller of this path is the scheduled catchup job.
     */
    suspend fun export(
        startDate: String,
        endDate: String,
        exportedByUserId: UUID,
        periodLabel: String = "",
        source: DatevExportSource = DatevExportSource.AUTO,
    ): DatevExportLogDto

    /**
     * Generate export bytes for the date range without persisting anything to disk
     * or recording an export log entry.
     *
     * Intended for cadence-driven (automated) monthly-close workflows that need to
     * bundle several DATEV exports into a single ZIP before committing them to the
     * archive. The caller is responsible for storage and audit trail.
     */
    suspend fun exportTransient(
        startDate: String,
        endDate: String,
        exportedByUserId: UUID,
    ): TransientExportResult
}
