package org.example.project.admin.features.dataexport

import org.example.project.core.data.remote.DatevExportApiClient
import org.example.project.core.domain.dto.datevexportlog.CatchupPreviewDto
import org.example.project.core.domain.dto.datevexportlog.CatchupResultDto
import org.example.project.core.domain.enums.setting.DatevExportCadence
import org.example.project.core.network.FileDownload

/** Records every ad-hoc export it is asked for. The catch-up methods are not exercised by the UI. */
internal class FakeDatevExportApiClient(
    var download: FileDownload = FileDownload(bytes = ByteArray(3), fileName = "x.zip", contentType = "application/zip"),
    var failWith: Throwable? = null,
) : DatevExportApiClient {
    val adHocCalls = mutableListOf<Triple<String, String, String>>()
    var lastExcludeKassenbuchLinked: Boolean? = null

    override suspend fun previewCatchup(
        type: String,
        cadence: DatevExportCadence,
    ): CatchupPreviewDto = CatchupPreviewDto(emptyList())

    override suspend fun runCatchup(
        type: String,
        cadence: DatevExportCadence,
    ): CatchupResultDto = CatchupResultDto(emptyList(), emptyList())

    override suspend fun exportAdHoc(
        type: String,
        startDate: String,
        endDate: String,
        excludeKassenbuchLinked: Boolean,
    ): FileDownload {
        adHocCalls += Triple(type, startDate, endDate)
        lastExcludeKassenbuchLinked = excludeKassenbuchLinked
        failWith?.let { throw it }
        return download
    }
}
