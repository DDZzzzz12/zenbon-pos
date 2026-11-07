package org.example.project.core.data.remote

import org.example.project.core.domain.dto.datevexportlog.CatchupPreviewDto
import org.example.project.core.domain.dto.datevexportlog.CatchupResultDto
import org.example.project.core.domain.enums.setting.DatevExportCadence
import org.example.project.core.network.FileDownload

/**
 * Stage C Batch 8: throws-style.
 */
interface DatevExportApiClient {
    suspend fun previewCatchup(
        type: String,
        cadence: DatevExportCadence,
    ): CatchupPreviewDto

    suspend fun runCatchup(
        type: String,
        cadence: DatevExportCadence,
    ): CatchupResultDto

    suspend fun exportAdHoc(
        type: String,
        startDate: String,
        endDate: String,
        excludeKassenbuchLinked: Boolean = true,
    ): FileDownload
}
