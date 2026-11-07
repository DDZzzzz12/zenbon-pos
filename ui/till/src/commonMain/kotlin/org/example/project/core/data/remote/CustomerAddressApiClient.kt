/**
 * API client interface for customer address operations.
 *
 * Stage C Batch 7: throws-style.
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.customer.CustomerAddressDto

interface CustomerAddressApiClient {
    suspend fun getCustomerAddresses(customerId: String): List<CustomerAddressDto.CustomerAddressResponseDto>

    suspend fun createAddress(request: CustomerAddressDto.CreateCustomerAddressRequestDto): CustomerAddressDto.CustomerAddressResponseDto
}
