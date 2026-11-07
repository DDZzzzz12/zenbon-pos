/**
 * Ktor-based implementation of the supplier (creditor) master-data API client.
 *
 * Throws-style, like every other client here: `apiCall` decodes a non-2xx into a
 * `PosApiException` carrying its `PosErrorCode`, so a 409 `SUPPLIER_VAT_ID_TAKEN` reaches the
 * caller typed.
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.SupplierApiClient
import org.example.project.core.domain.dto.supplier.SupplierDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorSupplierApiClient(
    private val httpCall: HttpCall,
) : SupplierApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/suppliers"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun list(
        provisionalOnly: Boolean,
        search: String?,
        sort: String,
        ascending: Boolean,
        limit: Int,
        offset: Long,
    ): SupplierDto.ListResponseDto =
        httpCall.httpClient.apiCall {
            get(url(BASE_ENDPOINT)) {
                parameter("provisionalOnly", provisionalOnly.toString())
                // Sent only when there is a term: an empty `search=` would be a filter the server
                // then has to recognise as blank, rather than a request that simply carries none.
                search?.takeIf { it.isNotBlank() }?.let { parameter("search", it) }
                parameter("sort", sort)
                parameter("ascending", ascending.toString())
                parameter("limit", limit.toString())
                parameter("offset", offset.toString())
            }
        }

    override suspend fun get(id: Long): SupplierDto.ResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/$id"))
        }

    override suspend fun create(request: SupplierDto.CreateRequestDto): SupplierDto.ResponseDto =
        httpCall.httpClient.apiCall {
            post(url(BASE_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun update(
        id: Long,
        request: SupplierDto.UpdateRequestDto,
    ): SupplierDto.ResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$id")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
}
