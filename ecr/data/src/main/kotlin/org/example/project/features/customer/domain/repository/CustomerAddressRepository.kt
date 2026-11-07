package org.example.project.features.customer.domain.repository

import org.example.project.core.domain.dto.customer.CustomerAddressDto
import org.example.project.features.customer.domain.models.CustomerAddress
import java.util.UUID

/**
 * Repository interface for CustomerAddress entity CRUD operations.
 *
 * Defines the contract for customer address data access operations including
 * standard CRUD operations and specialized queries for address management.
 * Returns plain entities or nullable types; exceptions propagate naturally
 * to the service layer.
 */
interface CustomerAddressRepository {
    /**
     * Creates a new customer address record.
     *
     * @param request Address data for creation
     * @return Created CustomerAddress entity
     */
    fun createAddress(request: CustomerAddressDto.CreateCustomerAddressRequestDto): CustomerAddress

    /**
     * Retrieves an address by its unique identifier.
     *
     * @param addressId UUID of the address to retrieve
     * @return CustomerAddress entity or null if not found
     */
    fun getAddressById(addressId: UUID): CustomerAddress?

    /**
     * Retrieves all addresses for a specific customer.
     *
     * @param customerId UUID of the customer
     * @param addressType Optional filter by address type
     * @return List of CustomerAddress entities
     */
    fun getAddressesByCustomerId(
        customerId: UUID,
        addressType: String? = null,
    ): List<CustomerAddress>

    /**
     * Retrieves the default address for a customer.
     *
     * @param customerId UUID of the customer
     * @return CustomerAddress entity or null if no default address
     */
    fun getDefaultAddress(customerId: UUID): CustomerAddress?

    /**
     * Retrieves all addresses with optional pagination.
     *
     * @param limit Maximum number of records to return (default: 50)
     * @param offset Number of records to skip (default: 0)
     * @return List of CustomerAddress entities
     */
    fun getAllAddresses(
        limit: Int = 50,
        offset: Int = 0,
    ): List<CustomerAddress>

    /**
     * Updates an existing customer address.
     *
     * @param addressId UUID of the address to update
     * @param request Updated address information
     * @return Updated CustomerAddress entity or null if not found
     */
    fun updateAddress(
        addressId: UUID,
        request: CustomerAddressDto.UpdateCustomerAddressRequestDto,
    ): CustomerAddress?

    /**
     * Deletes an address by its unique identifier (hard delete).
     *
     * @param addressId UUID of the address to delete
     * @throws NoSuchElementException if address not found
     */
    fun deleteAddress(addressId: UUID)

    /**
     * Sets an address as the default for a customer.
     *
     * @param addressId UUID of the address to set as default
     * @throws NoSuchElementException if address not found
     */
    fun setDefaultAddress(addressId: UUID)

    /**
     * Counts the number of addresses for a customer.
     *
     * @param customerId UUID of the customer
     * @return Count of addresses
     */
    fun countAddressesByCustomerId(customerId: UUID): Int
}
