package org.example.project.features.reservation.data.remote.impl

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.domain.dto.reservation.ReservationDto
import org.example.project.core.domain.dto.reservation.ReservationRuleDto
import org.example.project.core.domain.enums.reservation.ReservationStatus
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall
import org.example.project.features.reservation.data.remote.ReservationApiClient

class KtorReservationApiClient(
    private val httpCall: HttpCall,
) : ReservationApiClient {
    companion object {
        private const val RESERVATIONS_ENDPOINT = "api/reservations"
        private const val RULES_ENDPOINT = "api/reservation-rules"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getReservationsByDate(
        date: String,
        status: ReservationStatus?,
        limit: Int?,
        offset: Int?,
    ): List<ReservationDto.ResponseDto> =
        httpCall.httpClient.apiCall {
            get(url(RESERVATIONS_ENDPOINT)) {
                parameter("date", date)
                status?.let { parameter("status", it.name) }
                limit?.let { parameter("limit", it) }
                offset?.let { parameter("offset", it) }
            }
        }

    override suspend fun getReservationById(id: Long): ReservationDto.ResponseDto = httpCall.httpClient.apiCall { get(url("$RESERVATIONS_ENDPOINT/$id")) }

    override suspend fun createReservation(request: ReservationDto.CreateRequestDto): ReservationDto.ResponseDto =
        httpCall.httpClient.apiCall {
            post(url(RESERVATIONS_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updateReservation(
        id: Long,
        request: ReservationDto.UpdateRequestDto,
    ): ReservationDto.ResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$RESERVATIONS_ENDPOINT/$id")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updateReservationStatus(
        id: Long,
        request: ReservationDto.UpdateStatusRequestDto,
    ): ReservationDto.ResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$RESERVATIONS_ENDPOINT/$id/status")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun deleteReservation(id: Long) {
        httpCall.httpClient.apiCall<Unit> { delete(url("$RESERVATIONS_ENDPOINT/$id")) }
    }

    override suspend fun searchReservations(
        query: String,
        limit: Int?,
        offset: Int?,
    ): List<ReservationDto.ResponseDto> =
        httpCall.httpClient.apiCall {
            get(url("$RESERVATIONS_ENDPOINT/search")) {
                parameter("q", query)
                limit?.let { parameter("limit", it) }
                offset?.let { parameter("offset", it) }
            }
        }

    override suspend fun checkAvailability(
        date: String,
        time: String?,
        partySize: Int?,
    ): ReservationDto.AvailabilityResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$RESERVATIONS_ENDPOINT/availability")) {
                parameter("date", date)
                time?.let { parameter("time", it) }
                partySize?.let { parameter("partySize", it) }
            }
        }

    override suspend fun getRulesByDateRange(
        startDate: String,
        endDate: String,
    ): List<ReservationRuleDto.ResponseDto> =
        httpCall.httpClient.apiCall {
            get(url(RULES_ENDPOINT)) {
                parameter("startDate", startDate)
                parameter("endDate", endDate)
            }
        }

    override suspend fun getRuleById(id: Long): ReservationRuleDto.ResponseDto = httpCall.httpClient.apiCall { get(url("$RULES_ENDPOINT/$id")) }

    override suspend fun createRule(request: ReservationRuleDto.CreateRequestDto): ReservationRuleDto.ResponseDto =
        httpCall.httpClient.apiCall {
            post(url(RULES_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updateRule(
        id: Long,
        request: ReservationRuleDto.UpdateRequestDto,
    ): ReservationRuleDto.ResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$RULES_ENDPOINT/$id")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun deleteRule(id: Long) {
        httpCall.httpClient.apiCall<Unit> { delete(url("$RULES_ENDPOINT/$id")) }
    }

    // Table Assignments

    override suspend fun autoAssign(reservationId: Long): ReservationDto.AutoAssignResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$RESERVATIONS_ENDPOINT/$reservationId/auto-assign"))
        }

    override suspend fun manualAssign(
        reservationId: Long,
        request: ReservationDto.ManualAssignRequestDto,
    ): List<ReservationDto.AssignmentDto> =
        httpCall.httpClient.apiCall {
            post(url("$RESERVATIONS_ENDPOINT/$reservationId/assign-tables")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun unassignAll(reservationId: Long) {
        httpCall.httpClient.apiCall<Unit> {
            delete(url("$RESERVATIONS_ENDPOINT/$reservationId/unassign-tables"))
        }
    }

    override suspend fun getAssignedTables(reservationId: Long): List<ReservationDto.AssignmentDto> =
        httpCall.httpClient.apiCall { get(url("$RESERVATIONS_ENDPOINT/$reservationId/tables")) }

    override suspend fun bulkAutoAssign(request: ReservationDto.BulkAutoAssignRequestDto): ReservationDto.BulkAutoAssignResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$RESERVATIONS_ENDPOINT/bulk-auto-assign")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
}
