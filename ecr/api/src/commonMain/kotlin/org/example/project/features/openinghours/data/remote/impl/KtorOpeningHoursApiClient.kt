package org.example.project.features.openinghours.data.remote.impl

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.domain.dto.openinghours.OpeningHoursDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall
import org.example.project.features.openinghours.data.remote.OpeningHoursApiClient

class KtorOpeningHoursApiClient(
    private val httpCall: HttpCall,
) : OpeningHoursApiClient {
    companion object {
        private const val ENDPOINT = "api/opening-hours"
        private const val OVERRIDES_ENDPOINT = "api/opening-hours/overrides"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getAll(): List<OpeningHoursDto.WeeklyResponseDto> = httpCall.httpClient.apiCall { get(url(ENDPOINT)) }

    override suspend fun bulkUpdate(request: OpeningHoursDto.BulkUpdateRequestDto): List<OpeningHoursDto.WeeklyResponseDto> =
        httpCall.httpClient.apiCall {
            put(url(ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun getOverridesByDateRange(
        startDate: String,
        endDate: String,
    ): List<OpeningHoursDto.OverrideResponseDto> =
        httpCall.httpClient.apiCall {
            get(url(OVERRIDES_ENDPOINT)) {
                parameter("startDate", startDate)
                parameter("endDate", endDate)
            }
        }

    override suspend fun createOverride(request: OpeningHoursDto.CreateOverrideRequestDto): OpeningHoursDto.OverrideResponseDto =
        httpCall.httpClient.apiCall {
            post(url(OVERRIDES_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updateOverride(
        id: Long,
        request: OpeningHoursDto.UpdateOverrideRequestDto,
    ): OpeningHoursDto.OverrideResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$OVERRIDES_ENDPOINT/$id")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun deleteOverride(id: Long) {
        httpCall.httpClient.apiCall<Unit> { delete(url("$OVERRIDES_ENDPOINT/$id")) }
    }
}
