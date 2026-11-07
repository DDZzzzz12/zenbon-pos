package org.example.project.core.domain.dto.customer

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Container object for all CustomerAddress-related DTOs.
 *
 * Organizes DTOs by functionality: requests, responses, and utilities
 * to maintain clear separation of concerns and consistent API structure.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
object CustomerAddressDto {
    /**
     * Response DTO for CustomerAddress data.
     *
     * Contains complete address information for API responses including
     * geographic coordinates and delivery instructions.
     */
    @Serializable
    data class CustomerAddressResponseDto(
        val id: String, // UUID as String for JSON serialization
        val customerId: String,
        val addressType: String,
        val isDefault: Boolean,
        val street: String,
        val houseNumber: String,
        val apartment: String?,
        val postalCode: String,
        val city: String,
        val state: String?,
        val country: String,
        val additionalInfo: String?,
        val latitude: String?, // Decimal as String for JSON
        val longitude: String?, // Decimal as String for JSON
        val formattedAddress: String,
        val shortAddress: String,
        val createdAt: LocalDateTime,
        val updatedAt: LocalDateTime,
    ) {
        companion object
    }

    /**
     * Request DTO for creating a new CustomerAddress.
     *
     * Contains required and optional fields for address creation with validation.
     */
    @Serializable
    data class CreateCustomerAddressRequestDto(
        val customerId: String, // UUID as String
        val addressType: String = "delivery", // delivery, billing, shipping, other
        val isDefault: Boolean = false,
        val street: String,
        val houseNumber: String,
        val apartment: String? = null,
        val postalCode: String,
        val city: String,
        val state: String? = null,
        val country: String = "Germany",
        val additionalInfo: String? = null,
        val latitude: String? = null, // Decimal as String
        val longitude: String? = null, // Decimal as String
    ) {
        /**
         * Validates the create address request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(customerId.isNotBlank()) { "Customer ID is required" }

            require(addressType.isNotBlank()) { "Address type is required" }
            require(addressType in listOf("delivery", "billing", "shipping", "other")) {
                "Address type must be one of: delivery, billing, shipping, other"
            }

            require(street.isNotBlank()) { "Street is required" }
            require(street.length <= 255) { "Street must not exceed 255 characters" }

            require(houseNumber.isNotBlank()) { "House number is required" }
            require(houseNumber.length <= 10) { "House number must not exceed 10 characters" }

            apartment?.let {
                require(it.isNotBlank()) { "Apartment cannot be blank if provided" }
                require(it.length <= 50) { "Apartment must not exceed 50 characters" }
            }

            require(postalCode.isNotBlank()) { "Postal code is required" }
            require(postalCode.length <= 10) { "Postal code must not exceed 10 characters" }

            require(city.isNotBlank()) { "City is required" }
            require(city.length <= 100) { "City must not exceed 100 characters" }

            state?.let {
                require(it.isNotBlank()) { "State cannot be blank if provided" }
                require(it.length <= 100) { "State must not exceed 100 characters" }
            }

            require(country.isNotBlank()) { "Country is required" }
            require(country.length <= 100) { "Country must not exceed 100 characters" }

            additionalInfo?.let {
                require(it.length <= 10000) { "Additional info must not exceed 10000 characters" }
            }

            // Validate coordinates if provided
            if (latitude != null || longitude != null) {
                require(latitude != null && longitude != null) {
                    "Both latitude and longitude must be provided together"
                }
            }
        }
    }

    /**
     * Request DTO for updating an existing CustomerAddress.
     *
     * All fields are optional to support partial updates.
     */
    @Serializable
    data class UpdateCustomerAddressRequestDto(
        val addressType: String? = null,
        val isDefault: Boolean? = null,
        val street: String? = null,
        val houseNumber: String? = null,
        val apartment: String? = null,
        val postalCode: String? = null,
        val city: String? = null,
        val state: String? = null,
        val country: String? = null,
        val additionalInfo: String? = null,
        val latitude: String? = null,
        val longitude: String? = null,
    ) {
        /**
         * Validates the update address request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            addressType?.let {
                require(it.isNotBlank()) { "Address type cannot be blank" }
                require(it in listOf("delivery", "billing", "shipping", "other")) {
                    "Address type must be one of: delivery, billing, shipping, other"
                }
            }

            street?.let {
                require(it.isNotBlank()) { "Street cannot be blank" }
                require(it.length <= 255) { "Street must not exceed 255 characters" }
            }

            houseNumber?.let {
                require(it.isNotBlank()) { "House number cannot be blank" }
                require(it.length <= 10) { "House number must not exceed 10 characters" }
            }

            apartment?.let {
                require(it.isNotBlank()) { "Apartment cannot be blank" }
                require(it.length <= 50) { "Apartment must not exceed 50 characters" }
            }

            postalCode?.let {
                require(it.isNotBlank()) { "Postal code cannot be blank" }
                require(it.length <= 10) { "Postal code must not exceed 10 characters" }
            }

            city?.let {
                require(it.isNotBlank()) { "City cannot be blank" }
                require(it.length <= 100) { "City must not exceed 100 characters" }
            }

            state?.let {
                require(it.isNotBlank()) { "State cannot be blank" }
                require(it.length <= 100) { "State must not exceed 100 characters" }
            }

            country?.let {
                require(it.isNotBlank()) { "Country cannot be blank" }
                require(it.length <= 100) { "Country must not exceed 100 characters" }
            }

            additionalInfo?.let {
                require(it.length <= 10000) { "Additional info must not exceed 10000 characters" }
            }
        }
    }

    /**
     * Summary DTO for CustomerAddress list responses.
     *
     * Lightweight version with essential address information for list views.
     */
    @Serializable
    data class CustomerAddressSummaryDto(
        val id: String,
        val addressType: String,
        val isDefault: Boolean,
        val shortAddress: String,
        val city: String,
        val country: String,
    )
}
