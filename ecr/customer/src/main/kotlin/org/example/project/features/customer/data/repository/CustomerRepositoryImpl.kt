/**
 * Implementation of Customer repository using Exposed ORM.
 *
 * Provides data access operations for Customer entities.
 * Returns plain entities; exceptions propagate to the service layer.
 *
 * Note: Database transactions are managed by the service layer (dbQuery),
 * repositories perform raw entity operations only.
 */
package org.example.project.features.customer.data.repository

import org.example.project.core.domain.dto.customer.CustomerDto
import org.example.project.features.customer.database.tables.CustomerTable
import org.example.project.features.customer.domain.models.Customer
import org.example.project.features.customer.domain.repository.CustomerRepository
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import java.util.UUID

class CustomerRepositoryImpl : CustomerRepository {
    override fun createCustomer(request: CustomerDto.CreateCustomerRequestDto): Customer {
        // Validate request
        request.validate()

        // Check for duplicate customer number
        val existingByNumber =
            Customer
                .find {
                    CustomerTable.customerNumber eq request.customerNumber
                }.firstOrNull()

        if (existingByNumber != null) {
            throw IllegalArgumentException("Customer number already exists: ${request.customerNumber}")
        }

        // Check for duplicate email if provided
        request.email?.let { email ->
            val existingByEmail =
                Customer
                    .find {
                        CustomerTable.email eq email
                    }.firstOrNull()

            if (existingByEmail != null) {
                throw IllegalArgumentException("Email already exists: $email")
            }
        }

        // Check for duplicate phone if provided
        request.phone?.let { phone ->
            val existingByPhone =
                Customer
                    .find {
                        CustomerTable.phone eq phone
                    }.firstOrNull()

            if (existingByPhone != null) {
                throw IllegalArgumentException("Phone already exists: $phone")
            }
        }

        // Create customer
        val now = currentTimeStamp()
        return Customer.new {
            customerNumber = request.customerNumber
            firstName = request.firstName
            lastName = request.lastName
            email = request.email
            phone = request.phone
            notes = request.notes
            marketingConsent = request.marketingConsent
            dataProcessingConsent = request.dataProcessingConsent
            isActive = true
            createdAt = now
            updatedAt = now
        }
    }

    override fun getCustomerById(customerId: UUID): Customer? = Customer.findById(customerId)

    override fun getCustomerByNumber(customerNumber: String): Customer? =
        Customer
            .find {
                CustomerTable.customerNumber eq customerNumber
            }.firstOrNull()

    override fun getCustomerByEmail(email: String): Customer? =
        Customer
            .find {
                CustomerTable.email eq email
            }.firstOrNull()

    override fun getCustomerByPhone(phone: String): Customer? =
        Customer
            .find {
                CustomerTable.phone eq phone
            }.firstOrNull()

    override fun getAllCustomers(
        limit: Int,
        offset: Int,
        active: Boolean?,
        searchQuery: String?,
    ): List<Customer> =
        Customer
            .find {
                var condition = CustomerTable.id.isNotNull()

                // Filter by active status if specified
                active?.let {
                    condition = condition and (CustomerTable.isActive eq it)
                }

                // Search filter if provided
                searchQuery?.let { query ->
                    val searchPattern = "%${query.lowercase()}%"
                    condition = condition and (
                        (CustomerTable.firstName.lowerCase() like searchPattern) or
                            (CustomerTable.lastName.lowerCase() like searchPattern) or
                            (CustomerTable.email.lowerCase() like searchPattern) or
                            (CustomerTable.phone like searchPattern) or
                            (CustomerTable.customerNumber like searchPattern)
                    )
                }

                condition
            }.limit(limit)
            .offset(offset.toLong())
            .toList()

    override fun updateCustomer(
        customerId: UUID,
        request: CustomerDto.UpdateCustomerRequestDto,
    ): Customer? {
        // Validate request
        request.validate()

        // Find customer
        val customer = Customer.findById(customerId) ?: return null

        // Check for duplicate email if being updated
        request.email?.let { email ->
            val existingByEmail =
                Customer
                    .find {
                        (CustomerTable.email eq email) and (CustomerTable.id neq customerId)
                    }.firstOrNull()

            if (existingByEmail != null) {
                throw IllegalArgumentException("Email already exists: $email")
            }
        }

        // Check for duplicate phone if being updated
        request.phone?.let { phone ->
            val existingByPhone =
                Customer
                    .find {
                        (CustomerTable.phone eq phone) and (CustomerTable.id neq customerId)
                    }.firstOrNull()

            if (existingByPhone != null) {
                throw IllegalArgumentException("Phone already exists: $phone")
            }
        }

        // Update fields
        request.firstName?.let { customer.firstName = it }
        request.lastName?.let { customer.lastName = it }
        request.email?.let { customer.email = it }
        request.phone?.let { customer.phone = it }
        request.notes?.let { customer.notes = it }
        request.marketingConsent?.let { customer.marketingConsent = it }
        request.dataProcessingConsent?.let { customer.dataProcessingConsent = it }
        request.isActive?.let { customer.isActive = it }

        customer.updatedAt = currentTimeStamp()

        return customer
    }

    override fun deleteCustomer(customerId: UUID) {
        val customer =
            Customer.findById(customerId)
                ?: throw NoSuchElementException("Customer not found: $customerId")

        // Soft delete
        customer.isActive = false
        customer.updatedAt = currentTimeStamp()
    }

    override fun existsByCustomerNumber(
        customerNumber: String,
        excludeCustomerId: UUID?,
    ): Boolean =
        Customer
            .find {
                var condition = CustomerTable.customerNumber eq customerNumber
                excludeCustomerId?.let {
                    condition = condition and (CustomerTable.id neq it)
                }
                condition
            }.count() > 0

    override fun existsByEmail(
        email: String,
        excludeCustomerId: UUID?,
    ): Boolean =
        Customer
            .find {
                var condition = CustomerTable.email eq email
                excludeCustomerId?.let {
                    condition = condition and (CustomerTable.id neq it)
                }
                condition
            }.count() > 0

    override fun existsByPhone(
        phone: String,
        excludeCustomerId: UUID?,
    ): Boolean =
        Customer
            .find {
                var condition = CustomerTable.phone eq phone
                excludeCustomerId?.let {
                    condition = condition and (CustomerTable.id neq it)
                }
                condition
            }.count() > 0

    override fun updateLastPurchase(customerId: UUID) {
        val customer =
            Customer.findById(customerId)
                ?: throw NoSuchElementException("Customer not found: $customerId")

        customer.lastPurchaseAt = currentTimeStamp()
        customer.updatedAt = currentTimeStamp()
    }
}
