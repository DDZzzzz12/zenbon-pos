package org.example.project.features.customer.domain.repository

import org.example.project.core.domain.dto.customer.CustomerDto
import org.example.project.features.customer.domain.models.Customer
import java.util.UUID

/**
 * Repository interface for Customer entity CRUD operations.
 *
 * Defines the contract for customer data access operations including
 * standard CRUD operations and specialized queries for customer management.
 * Returns plain entities or nullable types; exceptions propagate naturally
 * to the service layer.
 */
interface CustomerRepository {
    /**
     * Creates a new customer record with the provided information.
     *
     * @param request Customer data for creation
     * @return Created Customer entity
     */
    fun createCustomer(request: CustomerDto.CreateCustomerRequestDto): Customer

    /**
     * Retrieves a customer by their unique identifier.
     *
     * @param customerId UUID of the customer to retrieve
     * @return Customer entity or null if not found
     */
    fun getCustomerById(customerId: UUID): Customer?

    /**
     * Retrieves a customer by their customer number.
     *
     * @param customerNumber Unique customer number
     * @return Customer entity or null if not found
     */
    fun getCustomerByNumber(customerNumber: String): Customer?

    /**
     * Retrieves a customer by their email address.
     *
     * @param email Customer's email address
     * @return Customer entity or null if not found
     */
    fun getCustomerByEmail(email: String): Customer?

    /**
     * Retrieves a customer by their phone number.
     *
     * @param phone Customer's phone number
     * @return Customer entity or null if not found
     */
    fun getCustomerByPhone(phone: String): Customer?

    /**
     * Retrieves all customers with optional filtering and pagination.
     *
     * @param limit Maximum number of records to return (default: 50)
     * @param offset Number of records to skip (default: 0)
     * @param active Filter by active status (null for all)
     * @param searchQuery Optional search query for name, email, or phone
     * @return List of Customer entities
     */
    fun getAllCustomers(
        limit: Int = 50,
        offset: Int = 0,
        active: Boolean? = null,
        searchQuery: String? = null,
    ): List<Customer>

    /**
     * Updates an existing customer's information.
     *
     * @param customerId UUID of the customer to update
     * @param request Updated customer information
     * @return Updated Customer entity or null if not found
     */
    fun updateCustomer(
        customerId: UUID,
        request: CustomerDto.UpdateCustomerRequestDto,
    ): Customer?

    /**
     * Deletes a customer by their unique identifier (soft delete).
     *
     * @param customerId UUID of the customer to delete
     * @throws NoSuchElementException if customer not found
     */
    fun deleteCustomer(customerId: UUID)

    /**
     * Checks if a customer number already exists.
     *
     * @param customerNumber Customer number to check
     * @param excludeCustomerId Optional customer ID to exclude from check (for updates)
     * @return true if exists, false if available
     */
    fun existsByCustomerNumber(
        customerNumber: String,
        excludeCustomerId: UUID? = null,
    ): Boolean

    /**
     * Checks if an email already exists.
     *
     * @param email Email to check
     * @param excludeCustomerId Optional customer ID to exclude from check (for updates)
     * @return true if exists, false if available
     */
    fun existsByEmail(
        email: String,
        excludeCustomerId: UUID? = null,
    ): Boolean

    /**
     * Checks if a phone number already exists.
     *
     * @param phone Phone number to check
     * @param excludeCustomerId Optional customer ID to exclude from check (for updates)
     * @return true if exists, false if available
     */
    fun existsByPhone(
        phone: String,
        excludeCustomerId: UUID? = null,
    ): Boolean

    /**
     * Updates customer's last purchase timestamp.
     *
     * @param customerId UUID of the customer
     * @throws NoSuchElementException if customer not found
     */
    fun updateLastPurchase(customerId: UUID)
}
