/**
 * API client interface for customer operations.
 *
 * Stage C Batch 7: throws-style. Implementations raise
 * [org.example.project.core.network.error.PosApiException] /
 * [org.example.project.core.network.error.NetworkApiException] on failure.
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.customer.CustomerDto

interface CustomerApiClient {
    suspend fun getAllCustomers(
        limit: Int = 50,
        offset: Int = 0,
        active: Boolean? = null,
        search: String? = null,
    ): List<CustomerDto.CustomerResponseDto>

    suspend fun createCustomer(request: CustomerDto.CreateCustomerRequestDto): CustomerDto.CustomerResponseDto

    suspend fun getCustomerById(customerId: String): CustomerDto.CustomerResponseDto

    suspend fun searchCustomers(
        query: String,
        limit: Int = 10,
    ): List<CustomerDto.CustomerSummaryDto>
}
