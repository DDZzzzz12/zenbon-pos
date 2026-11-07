/**
 * CustomerAddress entity to DTO mapping functions.
 *
 * Provides conversion methods for CustomerAddress entities to their corresponding DTOs
 * for API responses and data transfer operations.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.customer.mapper

import org.example.project.core.domain.dto.customer.CustomerAddressDto
import org.example.project.features.customer.domain.models.CustomerAddress

/**
 * Converts CustomerAddress entity to CustomerAddressResponseDto.
 *
 * Maps all address fields including geographic coordinates, delivery information,
 * and formatted address strings to the API response format.
 *
 * @return CustomerAddressResponseDto with all address data
 */
fun CustomerAddress.toResponse(): CustomerAddressDto.CustomerAddressResponseDto =
    CustomerAddressDto.CustomerAddressResponseDto(
        id = this.id.value.toString(),
        customerId =
            this.customer.id.value
                .toString(),
        addressType = this.addressType,
        isDefault = this.isDefault,
        street = this.street,
        houseNumber = this.houseNumber,
        apartment = this.apartment,
        postalCode = this.postalCode,
        city = this.city,
        state = this.state,
        country = this.country,
        additionalInfo = this.additionalInfo,
        latitude = this.latitude?.toString(),
        longitude = this.longitude?.toString(),
        formattedAddress = this.formattedAddress,
        shortAddress = this.shortAddress,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt,
    )

fun CustomerAddress.toSummary(): CustomerAddressDto.CustomerAddressSummaryDto =
    CustomerAddressDto.CustomerAddressSummaryDto(
        id = this.id.toString(),
        addressType = this.addressType,
        isDefault = this.isDefault,
        shortAddress = this.shortAddress,
        city = this.city,
        country = this.country,
    )

/**
 * Extension function to create CustomerAddressResponseDto.fromEntity() functionality.
 *
 * Provides static factory method on the companion object for consistent
 * entity-to-DTO conversion across the service layer.
 *
 * @param entity CustomerAddress entity to convert
 * @return CustomerAddressResponseDto instance
 */
fun CustomerAddressDto.CustomerAddressResponseDto.Companion.fromEntity(entity: CustomerAddress): CustomerAddressDto.CustomerAddressResponseDto =
    entity.toResponse()
