/**
 * Implementation of CustomerAddress repository using Exposed ORM.
 *
 * Provides data access operations for CustomerAddress entities.
 * Returns plain entities; exceptions propagate to the service layer.
 *
 * Note: Database transactions are managed by the service layer (dbQuery),
 * repositories perform raw entity operations only.
 */
package org.example.project.features.customer.data.repository

import org.example.project.core.domain.dto.customer.CustomerAddressDto
import org.example.project.features.customer.database.tables.CustomerAddressTable
import org.example.project.features.customer.domain.models.Customer
import org.example.project.features.customer.domain.models.CustomerAddress
import org.example.project.features.customer.domain.repository.CustomerAddressRepository
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.and
import java.math.BigDecimal
import java.util.UUID

class CustomerAddressRepositoryImpl : CustomerAddressRepository {
    override fun createAddress(request: CustomerAddressDto.CreateCustomerAddressRequestDto): CustomerAddress {
        // Validate request
        request.validate()

        // Verify customer exists
        val customerId = UUID.fromString(request.customerId)
        val customer =
            Customer.findById(customerId)
                ?: throw NoSuchElementException("Customer not found: ${request.customerId}")

        // If this is set as default, unset any existing default for this customer
        if (request.isDefault) {
            CustomerAddress
                .find {
                    (CustomerAddressTable.customerId eq customerId) and
                        (CustomerAddressTable.isDefault eq true)
                }.forEach { it.isDefault = false }
        }

        // Parse coordinates if provided
        val latitude = request.latitude?.let { BigDecimal(it) }
        val longitude = request.longitude?.let { BigDecimal(it) }

        // Create address
        val now = currentTimeStamp()
        return CustomerAddress.new {
            this.customer = customer
            this.addressType = request.addressType
            this.isDefault = request.isDefault
            this.street = request.street
            this.houseNumber = request.houseNumber
            this.apartment = request.apartment
            this.postalCode = request.postalCode
            this.city = request.city
            this.state = request.state
            this.country = request.country
            this.additionalInfo = request.additionalInfo
            this.latitude = latitude
            this.longitude = longitude
            this.createdAt = now
            this.updatedAt = now
        }
    }

    override fun getAddressById(addressId: UUID): CustomerAddress? = CustomerAddress.findById(addressId)

    override fun getAddressesByCustomerId(
        customerId: UUID,
        addressType: String?,
    ): List<CustomerAddress> {
        // Verify customer exists
        Customer.findById(customerId)
            ?: throw NoSuchElementException("Customer not found: $customerId")

        return CustomerAddress
            .find {
                var condition = CustomerAddressTable.customerId eq customerId

                addressType?.let {
                    condition = condition and (CustomerAddressTable.addressType eq it)
                }

                condition
            }.toList()
    }

    override fun getDefaultAddress(customerId: UUID): CustomerAddress? {
        // Verify customer exists
        Customer.findById(customerId)
            ?: throw NoSuchElementException("Customer not found: $customerId")

        return CustomerAddress
            .find {
                (CustomerAddressTable.customerId eq customerId) and
                    (CustomerAddressTable.isDefault eq true)
            }.firstOrNull()
    }

    override fun getAllAddresses(
        limit: Int,
        offset: Int,
    ): List<CustomerAddress> =
        CustomerAddress
            .all()
            .limit(limit)
            .offset(offset.toLong())
            .toList()

    override fun updateAddress(
        addressId: UUID,
        request: CustomerAddressDto.UpdateCustomerAddressRequestDto,
    ): CustomerAddress? {
        // Validate request
        request.validate()

        // Find address
        val address = CustomerAddress.findById(addressId) ?: return null

        // If setting as default, unset other defaults for this customer
        if (request.isDefault == true) {
            CustomerAddress
                .find {
                    (CustomerAddressTable.customerId eq address.customer.id) and
                        (CustomerAddressTable.isDefault eq true) and
                        (CustomerAddressTable.id neq addressId)
                }.forEach { it.isDefault = false }
        }

        // Update fields
        request.addressType?.let { address.addressType = it }
        request.isDefault?.let { address.isDefault = it }
        request.street?.let { address.street = it }
        request.houseNumber?.let { address.houseNumber = it }
        request.apartment?.let { address.apartment = it }
        request.postalCode?.let { address.postalCode = it }
        request.city?.let { address.city = it }
        request.state?.let { address.state = it }
        request.country?.let { address.country = it }
        request.additionalInfo?.let { address.additionalInfo = it }
        request.latitude?.let { address.latitude = BigDecimal(it) }
        request.longitude?.let { address.longitude = BigDecimal(it) }

        address.updatedAt = currentTimeStamp()

        return address
    }

    override fun deleteAddress(addressId: UUID) {
        val address =
            CustomerAddress.findById(addressId)
                ?: throw NoSuchElementException("Address not found: $addressId")

        // Hard delete
        address.delete()
    }

    override fun setDefaultAddress(addressId: UUID) {
        val address =
            CustomerAddress.findById(addressId)
                ?: throw NoSuchElementException("Address not found: $addressId")

        // Unset all other defaults for this customer
        CustomerAddress
            .find {
                (CustomerAddressTable.customerId eq address.customer.id) and
                    (CustomerAddressTable.isDefault eq true) and
                    (CustomerAddressTable.id neq addressId)
            }.forEach { it.isDefault = false }

        // Set this address as default
        address.isDefault = true
        address.updatedAt = currentTimeStamp()
    }

    override fun countAddressesByCustomerId(customerId: UUID): Int {
        // Verify customer exists
        Customer.findById(customerId)
            ?: throw NoSuchElementException("Customer not found: $customerId")

        return CustomerAddress
            .find {
                CustomerAddressTable.customerId eq customerId
            }.count()
            .toInt()
    }
}
