package org.example.project.features.datevexportlog.domain.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.setting.DatevExportSource
import org.example.project.features.datevexportlog.domain.models.DatevExportLog
import org.example.project.features.datevexportlog.domain.models.DatevExportType
import java.util.UUID

interface DatevExportLogRepository {
    suspend fun insert(
        exportType: DatevExportType,
        periodStart: LocalDateTime,
        periodEnd: LocalDateTime,
        exportedByUserId: UUID,
        source: DatevExportSource,
        fileHash: String,
        fileName: String,
        rowCount: Int,
        entryCount: Int,
        periodFolder: String,
    ): DatevExportLog

    suspend fun findById(id: Long): DatevExportLog?

    /**
     * Logs whose period overlaps `[periodStart, periodEnd)` for the given type (or any type if null).
     *
     * @param sourceFilter When non-null, only rows with this source are returned.
     *                     Defaults to [DatevExportSource.AUTO] so callers used for coverage/catchup
     *                     detection automatically ignore MANUAL rows.
     *                     Pass `null` to return all sources (e.g. audit/display queries).
     */
    suspend fun findOverlapping(
        periodStart: LocalDateTime,
        periodEnd: LocalDateTime,
        exportType: DatevExportType? = null,
        sourceFilter: DatevExportSource? = DatevExportSource.AUTO,
    ): List<DatevExportLog>

    /**
     * Returns the latest `periodEnd` value among all log rows of the given [type].
     *
     * @param sourceFilter When non-null, only rows with this source are considered.
     *                     Defaults to [DatevExportSource.AUTO] so the catchup cursor
     *                     is not advanced by MANUAL exports.
     */
    suspend fun findLatestEnd(
        type: DatevExportType,
        sourceFilter: DatevExportSource? = DatevExportSource.AUTO,
    ): LocalDateTime?
}
