package org.example.project.features.scheduler.data.remote.impl

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.domain.dto.scheduler.SchedulerDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall
import org.example.project.features.scheduler.data.remote.ScheduledJobApiClient

class KtorScheduledJobApiClient(
    private val httpCall: HttpCall,
) : ScheduledJobApiClient {
    companion object {
        private const val BASE = "api/scheduled-jobs"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun list(): List<SchedulerDto.ScheduledJobResponseDto> = httpCall.httpClient.apiCall { get(url(BASE)) }

    override suspend fun getDetail(id: String): SchedulerDto.ScheduledJobDetailDto = httpCall.httpClient.apiCall { get(url("$BASE/$id")) }

    override suspend fun runNow(id: String): SchedulerDto.JobExecutionResponseDto = httpCall.httpClient.apiCall { post(url("$BASE/$id/run")) }

    override suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ): SchedulerDto.ScheduledJobResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE/$id/enabled")) {
                contentType(ContentType.Application.Json)
                setBody(SchedulerDto.SetJobEnabledRequestDto(enabled))
            }
        }

    override suspend fun listExecutions(
        id: String,
        limit: Int,
        offset: Int,
    ): SchedulerDto.JobExecutionPageDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE/$id/executions")) {
                parameter("limit", limit)
                parameter("offset", offset)
            }
        }

    override suspend fun listTypes(): List<SchedulerDto.JobTypeInfoDto> = httpCall.httpClient.apiCall { get(url("$BASE/types")) }
}
