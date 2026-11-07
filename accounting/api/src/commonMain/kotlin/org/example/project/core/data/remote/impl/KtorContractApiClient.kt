/**
 * Ktor-based implementation of the Verträge API client.
 *
 * Throws-style, like every other client here: `apiCall` decodes a non-2xx into a
 * `PosApiException` carrying its `PosErrorCode`, so a 404 reaches the caller typed. `POST` answers
 * 201 with the created contract as its body, so it decodes like any other response.
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.ContractApiClient
import org.example.project.core.domain.dto.contract.ContractAlertDto
import org.example.project.core.domain.dto.contract.ContractDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorContractApiClient(
    private val httpCall: HttpCall,
) : ContractApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/contracts"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun list(
        includeInactive: Boolean,
        search: String?,
    ): ContractDto.ListResponseDto =
        httpCall.httpClient.apiCall {
            get(url(BASE_ENDPOINT)) {
                parameter("includeInactive", includeInactive.toString())
                search?.takeIf { it.isNotBlank() }?.let { parameter("search", it) }
            }
        }

    override suspend fun get(contractId: Long): ContractDto.ResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/$contractId"))
        }

    override suspend fun create(request: ContractDto.CreateRequestDto): ContractDto.ResponseDto =
        httpCall.httpClient.apiCall {
            post(url(BASE_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun update(
        contractId: Long,
        request: ContractDto.UpdateRequestDto,
    ): ContractDto.ResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$contractId")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun instalments(contractId: Long): ContractDto.InstalmentListDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/$contractId/instalments"))
        }

    override suspend fun alerts(): ContractAlertDto.ListResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/alerts"))
        }
}
