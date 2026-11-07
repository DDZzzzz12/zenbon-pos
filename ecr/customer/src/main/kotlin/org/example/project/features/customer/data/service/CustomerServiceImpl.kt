/**
 * Implementation of CustomerService business logic.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, CustomerError> in Stage B Batch 1.
 */
package org.example.project.features.customer.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.customer.CustomerDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.customer.domain.repository.CustomerRepository
import org.example.project.features.customer.domain.service.CustomerService
import org.example.project.features.customer.mapper.fromEntity
import org.example.project.features.customer.mapper.toSummary
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.slf4j.LoggerFactory
import java.util.UUID

class CustomerServiceImpl(
    private val customerRepository: CustomerRepository,
) : CustomerService {
    private val logger = LoggerFactory.getLogger(CustomerServiceImpl::class.java)

    override suspend fun createCustomer(request: CustomerDto.CreateCustomerRequestDto): CustomerDto.CustomerResponseDto {
        logger.info("createCustomer: creating customer with number={}", request.customerNumber)
        return try {
            dbQuery {
                val customer = customerRepository.createCustomer(request)
                CustomerDto.CustomerResponseDto.fromEntity(customer)
            }
        } catch (e: IllegalArgumentException) {
            val msg = e.message ?: ""
            logger.warn("createCustomer: constraint violation - {}", msg)
            throw when {
                msg.contains("Customer number already exists") ->
                    customerNumberTaken(request.customerNumber)
                msg.contains("Email already exists") ->
                    emailTaken(request.email ?: "")
                msg.contains("Phone already exists") ->
                    phoneTaken(request.phone ?: "")
                else -> validationFailed(msg.ifBlank { "Invalid request" })
            }
        }
    }

    override suspend fun getCustomerById(customerId: UUID): CustomerDto.CustomerResponseDto =
        dbQuery {
            val customer =
                customerRepository.getCustomerById(customerId)
                    ?: throw customerNotFound("id=$customerId")
            CustomerDto.CustomerResponseDto.fromEntity(customer)
        }

    override suspend fun getCustomerByNumber(customerNumber: String): CustomerDto.CustomerResponseDto =
        dbQuery {
            val customer =
                customerRepository.getCustomerByNumber(customerNumber)
                    ?: throw customerNotFound("number=$customerNumber")
            CustomerDto.CustomerResponseDto.fromEntity(customer)
        }

    override suspend fun getCustomerByEmail(email: String): CustomerDto.CustomerResponseDto =
        dbQuery {
            val customer =
                customerRepository.getCustomerByEmail(email)
                    ?: throw customerNotFound("email=$email")
            CustomerDto.CustomerResponseDto.fromEntity(customer)
        }

    override suspend fun getCustomerByPhone(phone: String): CustomerDto.CustomerResponseDto =
        dbQuery {
            val customer =
                customerRepository.getCustomerByPhone(phone)
                    ?: throw customerNotFound("phone=$phone")
            CustomerDto.CustomerResponseDto.fromEntity(customer)
        }

    override suspend fun getAllCustomers(
        limit: Int,
        offset: Int,
        active: Boolean?,
        searchQuery: String?,
    ): List<CustomerDto.CustomerResponseDto> =
        dbQuery {
            val customers =
                customerRepository
                    .getAllCustomers(limit, offset, active, searchQuery)
                    .map { CustomerDto.CustomerResponseDto.fromEntity(it) }
            logger.debug("getAllCustomers: returned {} customers", customers.size)
            customers
        }

    override suspend fun updateCustomer(
        customerId: UUID,
        request: CustomerDto.UpdateCustomerRequestDto,
    ): CustomerDto.CustomerResponseDto {
        logger.info("updateCustomer: updating customer id={}", customerId)
        return try {
            dbQuery {
                val customer =
                    customerRepository.updateCustomer(customerId, request)
                        ?: throw customerNotFound("id=$customerId")
                CustomerDto.CustomerResponseDto.fromEntity(customer)
            }
        } catch (e: IllegalArgumentException) {
            val msg = e.message ?: ""
            logger.warn("updateCustomer: constraint violation for id={} - {}", customerId, msg)
            throw when {
                msg.contains("Email already exists") -> emailTaken(request.email ?: "")
                msg.contains("Phone already exists") -> phoneTaken(request.phone ?: "")
                else -> validationFailed(msg.ifBlank { "Invalid request" })
            }
        }
    }

    override suspend fun deleteCustomer(customerId: UUID) {
        logger.info("deleteCustomer: deleting customer id={}", customerId)
        try {
            dbQuery {
                customerRepository.deleteCustomer(customerId)
            }
        } catch (e: NoSuchElementException) {
            throw customerNotFound("id=$customerId")
        }
    }

    override suspend fun isCustomerNumberAvailable(
        customerNumber: String,
        excludeCustomerId: UUID?,
    ): Boolean =
        dbQuery {
            !customerRepository.existsByCustomerNumber(customerNumber, excludeCustomerId)
        }

    override suspend fun isEmailAvailable(
        email: String,
        excludeCustomerId: UUID?,
    ): Boolean =
        dbQuery {
            !customerRepository.existsByEmail(email, excludeCustomerId)
        }

    override suspend fun isPhoneAvailable(
        phone: String,
        excludeCustomerId: UUID?,
    ): Boolean =
        dbQuery {
            !customerRepository.existsByPhone(phone, excludeCustomerId)
        }

    override suspend fun searchCustomers(
        query: String,
        limit: Int,
    ): List<CustomerDto.CustomerSummaryDto> =
        dbQuery {
            val summaries =
                customerRepository
                    .getAllCustomers(limit = limit, searchQuery = query, active = true)
                    .map { it.toSummary() }
            logger.debug("searchCustomers: returned {} results for query={}", summaries.size, query)
            summaries
        }

    override suspend fun recordPurchase(customerId: UUID) {
        try {
            dbQuery {
                customerRepository.updateLastPurchase(customerId)
            }
        } catch (e: NoSuchElementException) {
            throw customerNotFound("id=$customerId")
        }
    }

    // --- PosException helpers ---

    private fun customerNotFound(detail: String) =
        PosException(
            PosErrorCode.RESOURCE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Customer not found ($detail)",
        )

    private fun customerNumberTaken(num: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.Conflict,
            "Customer number '$num' already taken",
        )

    private fun emailTaken(email: String) =
        PosException(
            PosErrorCode.EMAIL_ALREADY_TAKEN,
            HttpStatusCode.Conflict,
            "Email '$email' already taken",
        )

    private fun phoneTaken(phone: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.Conflict,
            "Phone '$phone' already taken",
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )
}
