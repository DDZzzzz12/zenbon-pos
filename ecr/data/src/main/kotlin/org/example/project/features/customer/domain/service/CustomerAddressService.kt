package org.example.project.features.customer.domain.service

import org.example.project.core.domain.dto.customer.CustomerAddressDto
import java.util.UUID

/**
 * Service interface for CustomerAddress business logic operations.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors;
 * the global StatusPages handler converts to application/problem+json.
 * Migrated from Result<T, CustomerAddressError> in Stage B Batch 1.
 */
interface CustomerAddressService {
    /**
     * Creates a new customer address with validation.
     */
    suspend fun createAddress(request: CustomerAddressDto.CreateCustomerAddressRequestDto): CustomerAddressDto.CustomerAddressResponseDto

    /**
     * Retrieves an address by its unique identifier.
     */
    suspend fun getAddressById(addressId: UUID): CustomerAddressDto.CustomerAddressResponseDto

    /**
     * Retrieves all addresses for a specific customer.
     */
    suspend fun getAddressesByCustomerId(
        customerId: UUID,
        addressType: String? = null,
    ): List<CustomerAddressDto.CustomerAddressResponseDto>

    /**
     * Retrieves the default address for a customer.
     */
    suspend fun getDefaultAddress(customerId: UUID): CustomerAddressDto.CustomerAddressResponseDto

    /**
     * Retrieves all addresses with optional pagination.
     */
    suspend fun getAllAddresses(
        limit: Int = 50,
        offset: Int = 0,
    ): List<CustomerAddressDto.CustomerAddressResponseDto>

    /**
     * Updates an existing customer address.
     */
    suspend fun updateAddress(
        addressId: UUID,
        request: CustomerAddressDto.UpdateCustomerAddressRequestDto,
    ): CustomerAddressDto.CustomerAddressResponseDto

    /**
     * Deletes an address by its unique identifier.
     */
    suspend fun deleteAddress(addressId: UUID)

    /**
     * Sets an address as the default for its customer.
     */
    suspend fun setDefaultAddress(addressId: UUID)

    /**
     * Counts the number of addresses for a customer.
     */
    suspend fun getAddressCount(customerId: UUID): Int

    /**
     * Returns true if an address has complete geographic coordinates.
     */
    suspend fun hasCoordinates(addressId: UUID): Boolean
}
