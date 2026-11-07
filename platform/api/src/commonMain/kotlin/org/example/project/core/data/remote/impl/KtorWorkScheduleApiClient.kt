package org.example.project.core.data.remote.impl

import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.WorkScheduleApiClient
import org.example.project.core.domain.dto.employee.WorkScheduleDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorWorkScheduleApiClient(
    private val httpCall: HttpCall,
) : WorkScheduleApiClient {
    companion object {
        private const val BASE = "api/employees"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getWeeklySchedule(employeeId: String): WorkScheduleDto.WeeklyScheduleResponseDto =
        httpCall.httpClient.apiCall { get(url("$BASE/$employeeId/schedule")) }

    override suspend fun updateDaySchedule(
        employeeId: String,
        request: WorkScheduleDto.UpdateDayRequestDto,
    ): WorkScheduleDto.WeeklyScheduleResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE/$employeeId/schedule/day")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updateWeekSchedule(
        employeeId: String,
        request: WorkScheduleDto.UpdateWeekRequestDto,
    ): WorkScheduleDto.WeeklyScheduleResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE/$employeeId/schedule")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
}
