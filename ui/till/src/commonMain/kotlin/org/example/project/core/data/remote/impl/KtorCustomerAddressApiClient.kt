/**
 * Ktor-based implementation of CustomerAddressApiClient.
 *
 * Stage C Batch 7: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.CustomerAddressApiClient
import org.example.project.core.domain.dto.customer.CustomerAddressDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorCustomerAddressApiClient(
    private val httpCall: HttpCall,
) : CustomerAddressApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/customer-addresses"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getCustomerAddresses(customerId: String): List<CustomerAddressDto.CustomerAddressResponseDto> =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/customer/$customerId")) }

    override suspend fun createAddress(request: CustomerAddressDto.CreateCustomerAddressRequestDto): CustomerAddressDto.CustomerAddressResponseDto =
        httpCall.httpClient.apiCall {
            post(url(BASE_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
}
