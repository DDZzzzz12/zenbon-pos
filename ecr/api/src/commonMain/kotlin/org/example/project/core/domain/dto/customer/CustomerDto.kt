package org.example.project.core.domain.dto.customer

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import org.example.project.core.utils.DsfinvkFieldLimits

/**
 * Container object for all Customer-related DTOs.
 *
 * Organizes DTOs by functionality: requests, responses, and utilities
 * to maintain clear separation of concerns and consistent API structure.
 *
 */
@Suppress("UnsafeOptInUsageError")
object CustomerDto {
    /**
     * Response DTO for Customer data.
     *
     * Contains complete customer information for API responses including
     * personal details, contact information, privacy consents, and loyalty statistics.
     */
    @Serializable
    data class CustomerResponseDto(
        val id: String, // UUID as String for JSON serialization
        val customerNumber: String,
        val firstName: String,
        val lastName: String,
        val fullName: String,
        val email: String?,
        val phone: String?,
        val notes: String?,
        val marketingConsent: Boolean,
        val dataProcessingConsent: Boolean,
        val lastPurchaseAt: LocalDateTime?,
        val isActive: Boolean,
        val createdAt: LocalDateTime,
        val updatedAt: LocalDateTime,
    ) {
        companion object
    }

    /**
     * Request DTO for creating a new Customer.
     *
     * Contains required and optional fields for customer creation with validation.
     */
    @Serializable
    data class CreateCustomerRequestDto(
        val customerNumber: String,
        val firstName: String,
        val lastName: String,
        val email: String? = null,
        val phone: String? = null,
        val notes: String? = null,
        val marketingConsent: Boolean = false,
        val dataProcessingConsent: Boolean = true,
    ) {
        /**
         * Validates the create customer request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(customerNumber.isNotBlank()) { "Customer number is required" }
            require(customerNumber.length <= 20) { "Customer number must not exceed 20 characters" }

            require(firstName.isNotBlank()) { "First name is required" }
            require(firstName.length <= 100) { "First name must not exceed 100 characters" }

            require(lastName.isNotBlank()) { "Last name is required" }
            require(lastName.length <= 100) { "Last name must not exceed 100 characters" }

            // M1: the exporter emits `first last` as KUNDE_NAME (max 50). The COMBINED value is
            // what must fit; the 100-char limits above are the DB column, not the CSV field.
            DsfinvkFieldLimits.requireMaxLength(
                DsfinvkFieldLimits.displayName(firstName, lastName),
                DsfinvkFieldLimits.KUNDE_NAME,
                "Customer name (KUNDE_NAME)",
            )

            email?.let {
                require(it.isNotBlank()) { "Email cannot be blank if provided" }
                require(it.contains("@")) { "Email must be a valid email address" }
                require(it.length <= 255) { "Email must not exceed 255 characters" }
            }

            phone?.let {
                require(it.isNotBlank()) { "Phone cannot be blank if provided" }
                require(it.length <= 20) { "Phone must not exceed 20 characters" }
            }

            notes?.let {
                require(it.length <= 10000) { "Notes must not exceed 10000 characters" }
            }
        }
    }

    /**
     * Request DTO for updating an existing Customer.
     *
     * All fields are optional to support partial updates.
     */
    @Serializable
    data class UpdateCustomerRequestDto(
        val firstName: String? = null,
        val lastName: String? = null,
        val email: String? = null,
        val phone: String? = null,
        val notes: String? = null,
        val marketingConsent: Boolean? = null,
        val dataProcessingConsent: Boolean? = null,
        val isActive: Boolean? = null,
    ) {
        /**
         * Validates the update customer request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            firstName?.let {
                require(it.isNotBlank()) { "First name cannot be blank" }
                require(it.length <= 100) { "First name must not exceed 100 characters" }
            }

            lastName?.let {
                require(it.isNotBlank()) { "Last name cannot be blank" }
                require(it.length <= 100) { "Last name must not exceed 100 characters" }
            }

            // M1: KUNDE_NAME is the combined `first last`. A partial update carries only the
            // half being changed, so the combined check applies when both are present.
            if (firstName != null && lastName != null) {
                DsfinvkFieldLimits.requireMaxLength(
                    DsfinvkFieldLimits.displayName(firstName, lastName),
                    DsfinvkFieldLimits.KUNDE_NAME,
                    "Customer name (KUNDE_NAME)",
                )
            } else {
                DsfinvkFieldLimits.requireMaxLength(firstName, DsfinvkFieldLimits.KUNDE_NAME, "Customer first name (KUNDE_NAME)")
                DsfinvkFieldLimits.requireMaxLength(lastName, DsfinvkFieldLimits.KUNDE_NAME, "Customer last name (KUNDE_NAME)")
            }

            email?.let {
                require(it.isNotBlank()) { "Email cannot be blank" }
                require(it.contains("@")) { "Email must be a valid email address" }
                require(it.length <= 255) { "Email must not exceed 255 characters" }
            }

            phone?.let {
                require(it.isNotBlank()) { "Phone cannot be blank" }
                require(it.length <= 20) { "Phone must not exceed 20 characters" }
            }

            notes?.let {
                require(it.length <= 10000) { "Notes must not exceed 10000 characters" }
            }
        }
    }

    /**
     * Summary DTO for Customer list responses.
     *
     * Lightweight version with essential customer information for list views.
     */
    @Serializable
    data class CustomerSummaryDto(
        val id: String,
        val customerNumber: String,
        val fullName: String,
        val email: String?,
        val phone: String?,
        val isActive: Boolean,
    )
}
