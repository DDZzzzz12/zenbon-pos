package org.example.project.core.data.remote

import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto

/**
 * Stage C Batch 8: throws-style.
 */
interface DatevExportLogApiClient {
    suspend fun findOverlapping(
        periodStart: String,
        periodEnd: String,
        exportType: String? = null,
    ): List<DatevExportLogDto>

    suspend fun downloadExport(logId: Long): ByteArray
}
