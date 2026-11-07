/**
 * Ktor implementation of DsfinvkApiClient.
 *
 * Stage C Batch 8: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.DsfinvkApiClient
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorDsfinvkApiClient(
    private val httpCall: HttpCall,
) : DsfinvkApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/v1/dsfinvk"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun exportZip(request: DsfinvkExportDto.ExportRequestDto): ByteArray =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/export")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun validate(
        startDate: String,
        endDate: String,
    ): DsfinvkExportDto.ValidationResultDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/validate")) {
                parameter("startDate", startDate)
                parameter("endDate", endDate)
            }
        }

    override suspend fun getStatistics(
        startDate: String,
        endDate: String,
    ): DsfinvkExportDto.ExportStatisticsDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/statistics")) {
                parameter("startDate", startDate)
                parameter("endDate", endDate)
            }
        }
}
