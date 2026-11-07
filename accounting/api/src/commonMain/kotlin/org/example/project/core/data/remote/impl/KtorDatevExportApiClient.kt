package org.example.project.core.data.remote.impl

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import org.example.project.core.data.remote.DatevExportApiClient
import org.example.project.core.domain.dto.datevexportlog.CatchupPreviewDto
import org.example.project.core.domain.dto.datevexportlog.CatchupResultDto
import org.example.project.core.domain.enums.setting.DatevExportCadence
import org.example.project.core.network.ExportWarningHeaders
import org.example.project.core.network.FileDownload
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall
import org.example.project.core.network.error.apiCallRaw

/**
 * Stage C Batch 8: throws-style.
 */
class KtorDatevExportApiClient(
    private val httpCall: HttpCall,
) : DatevExportApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/v1/datev/export"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun previewCatchup(
        type: String,
        cadence: DatevExportCadence,
    ): CatchupPreviewDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/catchup/preview")) {
                parameter("type", type)
                parameter("cadence", cadence.name)
            }
        }

    override suspend fun runCatchup(
        type: String,
        cadence: DatevExportCadence,
    ): CatchupResultDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/catchup")) {
                parameter("type", type)
                parameter("cadence", cadence.name)
            }
        }

    override suspend fun exportAdHoc(
        type: String,
        startDate: String,
        endDate: String,
        excludeKassenbuchLinked: Boolean,
    ): FileDownload {
        val resp =
            httpCall.httpClient.apiCallRaw {
                get(url("$BASE_ENDPOINT/ad-hoc")) {
                    parameter("type", type)
                    parameter("startDate", startDate)
                    parameter("endDate", endDate)
                    parameter("excludeKassenbuchLinked", excludeKassenbuchLinked.toString())
                }
            }
        val fileName =
            resp.headers[HttpHeaders.ContentDisposition]
                ?.let { runCatching { ContentDisposition.parse(it) }.getOrNull() }
                ?.parameter(ContentDisposition.Parameters.FileName)
        return FileDownload(
            bytes = resp.body(),
            fileName = fileName,
            contentType = resp.contentType()?.let { "${it.contentType}/${it.contentSubtype}" },
            warnings = ExportWarningHeaders.decode(resp.headers[ExportWarningHeaders.TEXT]),
            unassignedContra = ExportWarningHeaders.decodeUnassigned(resp.headers[ExportWarningHeaders.UNASSIGNED]),
        )
    }
}
