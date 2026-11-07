/**
 * Ktor-based implementation of the debtor master-data API client.
 *
 * Throws-style, like every other client here: `apiCall` decodes a non-2xx into a `PosApiException`
 * carrying its `PosErrorCode`, so a 409 `DEBTOR_IN_USE` or `DEBTOR_NUMBER_TAKEN` reaches the caller
 * typed rather than as a status code it has to interpret.
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.DebtorApiClient
import org.example.project.core.domain.dto.debtor.DebtorDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall
import org.example.project.core.network.error.apiCallRaw

class KtorDebtorApiClient(
    private val httpCall: HttpCall,
) : DebtorApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/debtors"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun list(
        activeOnly: Boolean,
        search: String?,
        limit: Int,
        offset: Long,
    ): DebtorDto.ListResponseDto =
        httpCall.httpClient.apiCall {
            get(url(BASE_ENDPOINT)) {
                parameter("activeOnly", activeOnly.toString())
                // Sent only when there is a term: an empty `search=` would be a filter the server
                // then has to recognise as blank, rather than a request that carries none.
                search?.takeIf { it.isNotBlank() }?.let { parameter("search", it) }
                parameter("limit", limit.toString())
                parameter("offset", offset.toString())
            }
        }

    override suspend fun get(id: Long): DebtorDto.ResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/$id"))
        }

    override suspend fun create(request: DebtorDto.CreateRequestDto): DebtorDto.ResponseDto =
        httpCall.httpClient.apiCall {
            post(url(BASE_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun update(
        id: Long,
        request: DebtorDto.UpdateRequestDto,
    ): DebtorDto.ResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$id")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    /** `apiCallRaw`, not `apiCall`: the server answers 204 with no body for a successful delete. */
    override suspend fun delete(id: Long) {
        httpCall.httpClient.apiCallRaw {
            delete(url("$BASE_ENDPOINT/$id"))
        }
    }
}
