package org.example.project.core.data.remote.impl

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.BookkeepingStartApiClient
import org.example.project.core.domain.dto.finance.BookkeepingStartDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

/**
 * Ktor-based implementation of the Buchungsbeginn API client.
 *
 * Throws-style, like every other client here: `apiCall` decodes a non-2xx into a `PosApiException`
 * carrying its `PosErrorCode`, so the 400 on a malformed date reaches the caller typed.
 */
class KtorBookkeepingStartApiClient(
    private val httpCall: HttpCall,
) : BookkeepingStartApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/finance/bookkeeping-start"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun get(): BookkeepingStartDto.ResponseDto = httpCall.httpClient.apiCall { get(url(BASE_ENDPOINT)) }

    override suspend fun acknowledge(): BookkeepingStartDto.ResponseDto = httpCall.httpClient.apiCall { post(url("$BASE_ENDPOINT/acknowledge")) }

    override suspend fun update(startDate: String): BookkeepingStartDto.ResponseDto =
        httpCall.httpClient.apiCall {
            put(url(BASE_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(BookkeepingStartDto.UpdateRequestDto(startDate = startDate))
            }
        }
}
