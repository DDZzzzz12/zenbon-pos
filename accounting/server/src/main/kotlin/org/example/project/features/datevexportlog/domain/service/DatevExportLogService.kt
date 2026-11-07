package org.example.project.features.datevexportlog.domain.service

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.domain.enums.setting.DatevExportSource
import org.example.project.features.datevexportlog.domain.models.DatevExportType
import java.util.UUID

interface DatevExportLogService {
    suspend fun record(
        exportType: DatevExportType,
        periodStart: LocalDateTime,
        periodEnd: LocalDateTime,
        exportedByUserId: UUID,
        source: DatevExportSource,
        fileBytes: ByteArray,
        fileName: String,
        rowCount: Int,
        entryCount: Int,
        periodFolder: String,
    ): DatevExportLogDto

    suspend fun findById(id: Long): DatevExportLogDto

    /**
     * @param sourceFilter When non-null, only rows with this source are returned.
     *                     Defaults to [DatevExportSource.AUTO] so coverage checks
     *                     automatically ignore MANUAL rows.
     *                     Pass `null` to return all sources (e.g. audit/display queries).
     */
    suspend fun findOverlapping(
        periodStart: LocalDateTime,
        periodEnd: LocalDateTime,
        exportType: DatevExportType? = null,
        sourceFilter: DatevExportSource? = DatevExportSource.AUTO,
    ): List<DatevExportLogDto>
}
