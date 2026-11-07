package org.example.project.core.data.remote.impl

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import org.example.project.core.data.remote.TableSessionApiClient
import org.example.project.core.domain.dto.table.TableSessionDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

/**
 * Ktor implementation of [TableSessionApiClient], following the shared-client
 * pattern: takes [HttpCall], resolves endpoints via [HttpCall.getBaseUrl], and
 * wraps every call in [apiCall] so non-2xx responses surface as
 * [org.example.project.core.network.error.PosApiException] (RFC 7807
 * ProblemDetail) and transport failures as
 * [org.example.project.core.network.error.NetworkApiException].
 */
class KtorTableSessionApiClient(
    private val httpCall: HttpCall,
) : TableSessionApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/tables/sessions"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getAllSessions(
        limit: Int?,
        offset: Int?,
        tableId: Long?,
        employeeId: Long?,
        completed: Boolean?,
        startDate: String?,
        endDate: String?,
    ): List<TableSessionDto.TableSessionResponseDto> =
        httpCall.httpClient.apiCall {
            get(url(BASE_ENDPOINT)) {
                limit?.let { parameter("limit", it) }
                offset?.let { parameter("offset", it) }
                tableId?.let { parameter("tableId", it) }
                employeeId?.let { parameter("employeeId", it) }
                completed?.let { parameter("completed", it) }
                startDate?.let { parameter("startDate", it) }
                endDate?.let { parameter("endDate", it) }
            }
        }

    override suspend fun getSessionById(id: Long): TableSessionDto.TableSessionResponseDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$id")) }
}
