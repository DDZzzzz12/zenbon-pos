/**
 * Implementation of CustomerAddressService business logic.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, CustomerAddressError> in
 * Stage B Batch 1.
 */
package org.example.project.features.customer.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.customer.CustomerAddressDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.customer.domain.repository.CustomerAddressRepository
import org.example.project.features.customer.domain.service.CustomerAddressService
import org.example.project.features.customer.mapper.fromEntity
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.slf4j.LoggerFactory
import java.util.UUID

class CustomerAddressServiceImpl(
    private val customerAddressRepository: CustomerAddressRepository,
) : CustomerAddressService {
    private val logger = LoggerFactory.getLogger(CustomerAddressServiceImpl::class.java)

    override suspend fun createAddress(request: CustomerAddressDto.CreateCustomerAddressRequestDto): CustomerAddressDto.CustomerAddressResponseDto {
        logger.info("createAddress: creating address for customerId={}", request.customerId)
        return try {
            dbQuery {
                val address = customerAddressRepository.createAddress(request)
                CustomerAddressDto.CustomerAddressResponseDto.fromEntity(address)
            }
        } catch (e: NoSuchElementException) {
            logger.warn("createAddress: customer not found customerId={}", request.customerId)
            throw customerNotFound(request.customerId)
        } catch (e: IllegalArgumentException) {
            logger.warn("createAddress: validation failed - {}", e.message)
            throw validationFailed(e.message ?: "Validation failed")
        }
    }

    override suspend fun getAddressById(addressId: UUID): CustomerAddressDto.CustomerAddressResponseDto =
        dbQuery {
            val address =
                customerAddressRepository.getAddressById(addressId)
                    ?: throw addressNotFound(addressId)
            CustomerAddressDto.CustomerAddressResponseDto.fromEntity(address)
        }

    override suspend fun getAddressesByCustomerId(
        customerId: UUID,
        addressType: String?,
    ): List<CustomerAddressDto.CustomerAddressResponseDto> =
        try {
            dbQuery {
                customerAddressRepository
                    .getAddressesByCustomerId(customerId, addressType)
                    .map { CustomerAddressDto.CustomerAddressResponseDto.fromEntity(it) }
            }
        } catch (e: NoSuchElementException) {
            throw customerNotFound(customerId.toString())
        }

    override suspend fun getDefaultAddress(customerId: UUID): CustomerAddressDto.CustomerAddressResponseDto =
        try {
            dbQuery {
                val address =
                    customerAddressRepository.getDefaultAddress(customerId)
                        ?: throw noDefaultAddress(customerId)
                CustomerAddressDto.CustomerAddressResponseDto.fromEntity(address)
            }
        } catch (e: NoSuchElementException) {
            throw customerNotFound(customerId.toString())
        }

    override suspend fun getAllAddresses(
        limit: Int,
        offset: Int,
    ): List<CustomerAddressDto.CustomerAddressResponseDto> =
        dbQuery {
            customerAddressRepository
                .getAllAddresses(limit, offset)
                .map { CustomerAddressDto.CustomerAddressResponseDto.fromEntity(it) }
        }

    override suspend fun updateAddress(
        addressId: UUID,
        request: CustomerAddressDto.UpdateCustomerAddressRequestDto,
    ): CustomerAddressDto.CustomerAddressResponseDto {
        logger.info("updateAddress: updating address id={}", addressId)
        return try {
            dbQuery {
                val address =
                    customerAddressRepository.updateAddress(addressId, request)
                        ?: throw addressNotFound(addressId)
                CustomerAddressDto.CustomerAddressResponseDto.fromEntity(address)
            }
        } catch (e: IllegalArgumentException) {
            logger.warn("updateAddress: validation failed for id={} - {}", addressId, e.message)
            throw validationFailed(e.message ?: "Validation failed")
        }
    }

    override suspend fun deleteAddress(addressId: UUID) {
        logger.info("deleteAddress: deleting address id={}", addressId)
        try {
            dbQuery {
                customerAddressRepository.deleteAddress(addressId)
            }
        } catch (e: NoSuchElementException) {
            throw addressNotFound(addressId)
        }
    }

    override suspend fun setDefaultAddress(addressId: UUID) {
        logger.info("setDefaultAddress: setting default address id={}", addressId)
        try {
            dbQuery {
                customerAddressRepository.setDefaultAddress(addressId)
            }
        } catch (e: NoSuchElementException) {
            throw addressNotFound(addressId)
        }
    }

    override suspend fun getAddressCount(customerId: UUID): Int =
        try {
            dbQuery {
                customerAddressRepository.countAddressesByCustomerId(customerId)
            }
        } catch (e: NoSuchElementException) {
            throw customerNotFound(customerId.toString())
        }

    override suspend fun hasCoordinates(addressId: UUID): Boolean =
        dbQuery {
            val address =
                customerAddressRepository.getAddressById(addressId)
                    ?: throw addressNotFound(addressId)
            address.hasCoordinates()
        }

    // --- PosException helpers ---

    private fun addressNotFound(id: UUID) =
        PosException(
            PosErrorCode.RESOURCE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "CustomerAddress with id=$id not found",
        )

    private fun customerNotFound(customerId: String) =
        PosException(
            PosErrorCode.RESOURCE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Customer with id=$customerId not found",
        )

    private fun noDefaultAddress(customerId: UUID) =
        PosException(
            PosErrorCode.RESOURCE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "No default address for customer id=$customerId",
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )
}
