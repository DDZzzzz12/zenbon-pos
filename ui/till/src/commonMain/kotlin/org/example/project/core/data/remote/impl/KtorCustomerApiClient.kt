/**
 * Ktor-based implementation of CustomerApiClient.
 *
 * Stage C Batch 7: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.CustomerApiClient
import org.example.project.core.domain.dto.customer.CustomerDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorCustomerApiClient(
    private val httpCall: HttpCall,
) : CustomerApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/customers"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getAllCustomers(
        limit: Int,
        offset: Int,
        active: Boolean?,
        search: String?,
    ): List<CustomerDto.CustomerResponseDto> =
        httpCall.httpClient.apiCall {
            get(url(BASE_ENDPOINT)) {
                parameter("limit", limit)
                parameter("offset", offset)
                active?.let { parameter("active", it) }
                search?.let { parameter("search", it) }
            }
        }

    override suspend fun createCustomer(request: CustomerDto.CreateCustomerRequestDto): CustomerDto.CustomerResponseDto =
        httpCall.httpClient.apiCall {
            post(url(BASE_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun getCustomerById(customerId: String): CustomerDto.CustomerResponseDto =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$customerId")) }

    override suspend fun searchCustomers(
        query: String,
        limit: Int,
    ): List<CustomerDto.CustomerSummaryDto> =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/search")) {
                parameter("q", query)
                parameter("limit", limit)
            }
        }
}
