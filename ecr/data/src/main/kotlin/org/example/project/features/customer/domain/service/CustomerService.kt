package org.example.project.features.customer.domain.service

import org.example.project.core.domain.dto.customer.CustomerDto
import java.util.UUID

/**
 * Service interface for Customer business logic operations.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors;
 * the global StatusPages handler converts to application/problem+json.
 * Migrated from Result<T, CustomerError> in Stage B Batch 1.
 */
interface CustomerService {
    /**
     * Creates a new customer with validation and duplicate checking.
     */
    suspend fun createCustomer(request: CustomerDto.CreateCustomerRequestDto): CustomerDto.CustomerResponseDto

    /**
     * Retrieves a customer by their unique identifier.
     */
    suspend fun getCustomerById(customerId: UUID): CustomerDto.CustomerResponseDto

    /**
     * Retrieves a customer by their unique customer number.
     */
    suspend fun getCustomerByNumber(customerNumber: String): CustomerDto.CustomerResponseDto

    /**
     * Retrieves a customer by their email address.
     */
    suspend fun getCustomerByEmail(email: String): CustomerDto.CustomerResponseDto

    /**
     * Retrieves a customer by their phone number.
     */
    suspend fun getCustomerByPhone(phone: String): CustomerDto.CustomerResponseDto

    /**
     * Retrieves all customers with optional filtering and pagination.
     */
    suspend fun getAllCustomers(
        limit: Int = 50,
        offset: Int = 0,
        active: Boolean? = null,
        searchQuery: String? = null,
    ): List<CustomerDto.CustomerResponseDto>

    /**
     * Updates an existing customer's information.
     */
    suspend fun updateCustomer(
        customerId: UUID,
        request: CustomerDto.UpdateCustomerRequestDto,
    ): CustomerDto.CustomerResponseDto

    /**
     * Deletes (soft-delete) a customer.
     */
    suspend fun deleteCustomer(customerId: UUID)

    /**
     * Checks if a customer number is available.
     */
    suspend fun isCustomerNumberAvailable(
        customerNumber: String,
        excludeCustomerId: UUID? = null,
    ): Boolean

    /**
     * Checks if an email is available.
     */
    suspend fun isEmailAvailable(
        email: String,
        excludeCustomerId: UUID? = null,
    ): Boolean

    /**
     * Checks if a phone number is available.
     */
    suspend fun isPhoneAvailable(
        phone: String,
        excludeCustomerId: UUID? = null,
    ): Boolean

    /**
     * Updates the last purchase timestamp for a customer.
     */
    suspend fun recordPurchase(customerId: UUID)

    /**
     * Searches for active customers and returns lightweight summary DTOs.
     */
    suspend fun searchCustomers(
        query: String,
        limit: Int = 10,
    ): List<CustomerDto.CustomerSummaryDto>
}
