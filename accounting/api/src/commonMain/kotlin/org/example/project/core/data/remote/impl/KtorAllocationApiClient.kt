/**
 * Ktor-based implementation of the Zuordnung API client.
 *
 * Throws-style, like every other client here: `apiCall` decodes a non-2xx into a
 * `PosApiException` carrying its `PosErrorCode`, so a 409 reaches the caller typed.
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.AllocationApiClient
import org.example.project.core.domain.dto.allocation.AllocationDto
import org.example.project.core.domain.dto.allocation.SuggestionDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorAllocationApiClient(
    private val httpCall: HttpCall,
) : AllocationApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/allocations"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun queue(
        filter: AllocationDto.QueueFilter,
        limit: Int,
        offset: Long,
    ): AllocationDto.QueueResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/queue")) {
                parameter("filter", filter.name)
                parameter("limit", limit.toString())
                parameter("offset", offset.toString())
            }
        }

    override suspend fun openInvoices(
        supplierId: Long?,
        limit: Int,
        offset: Long,
    ): AllocationDto.OpenInvoiceListDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/open-invoices")) {
                supplierId?.let { parameter("supplierId", it.toString()) }
                parameter("limit", limit.toString())
                parameter("offset", offset.toString())
            }
        }

    override suspend fun openSalesInvoices(
        debtorId: Long?,
        limit: Int,
        offset: Long,
    ): AllocationDto.OpenSalesInvoiceListDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/open-sales-invoices")) {
                debtorId?.let { parameter("debtorId", it.toString()) }
                parameter("limit", limit.toString())
                parameter("offset", offset.toString())
            }
        }

    override suspend fun openInstalments(
        limit: Int,
        offset: Long,
    ): AllocationDto.OpenInstalmentListDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/open-instalments")) {
                parameter("limit", limit.toString())
                parameter("offset", offset.toString())
            }
        }

    override suspend fun suggestions(transactionId: Long): SuggestionDto.ResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/suggestions")) {
                parameter("transactionId", transactionId.toString())
            }
        }

    override suspend fun allocate(request: AllocationDto.CreateRequestDto): AllocationDto.CreateResponseDto =
        httpCall.httpClient.apiCall {
            post(url(BASE_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun allocateAll(requests: List<AllocationDto.CreateRequestDto>): List<AllocationDto.CreateResponseDto> =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/batch")) {
                contentType(ContentType.Application.Json)
                setBody(requests)
            }
        }

    override suspend fun reverse(allocationId: Long) = httpCall.httpClient.apiCall<Unit> { delete(url("$BASE_ENDPOINT/$allocationId")) }
}
