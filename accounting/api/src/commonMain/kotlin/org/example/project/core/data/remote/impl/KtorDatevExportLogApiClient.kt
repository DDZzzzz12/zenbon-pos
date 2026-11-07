package org.example.project.core.data.remote.impl

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import org.example.project.core.data.remote.DatevExportLogApiClient
import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

/**
 * Stage C Batch 8: throws-style.
 */
class KtorDatevExportLogApiClient(
    private val httpCall: HttpCall,
) : DatevExportLogApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/v1/datev/export-log"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun findOverlapping(
        periodStart: String,
        periodEnd: String,
        exportType: String?,
    ): List<DatevExportLogDto> =
        httpCall.httpClient.apiCall {
            get(url(BASE_ENDPOINT)) {
                parameter("periodStart", periodStart)
                parameter("periodEnd", periodEnd)
                exportType?.let { parameter("exportType", it) }
            }
        }

    override suspend fun downloadExport(logId: Long): ByteArray =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/$logId/download"))
        }
}
