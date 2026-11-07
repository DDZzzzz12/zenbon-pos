/**
 * Data Transfer Objects for VAT API operations.
 *
 * Container object for all VAT-related DTOs including request validation,
 * response formatting, and data transformation utilities. Supports German
 * tax compliance requirements with proper VAT rate management.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.dto.setup

import kotlinx.serialization.Serializable
import kotlin.math.round

/**
 * Container object for all VAT-related DTOs.
 *
 * Organizes DTOs by functionality: requests, responses, and utilities
 * to maintain clear separation of concerns and consistent API structure.
 * Supports German VAT compliance with standard rates.
 */
object VatDto {
    /**
     * Response DTO for VAT data.
     *
     * Used for API responses when returning VAT information to clients.
     * Contains all public fields from the entity in a serializable format.
     */
    @Serializable
    data class VatResponseDto(
        /** VAT unique identifier */
        val vatId: String,
        /** VAT classification identifier (e.g., "STANDARD", "REDUCED") */
        val vatClass: String,
        /** VAT rate as decimal (e.g., 0.19 for 19%) */
        val vatRate: Double,
        /** VAT rate formatted as percentage for display (e.g., "19.00%") */
        val vatPercentage: String,
    ) {
        companion object
    }

    /**
     * Request DTO for creating new VAT rates.
     *
     * Used for API requests when creating new VAT configurations.
     * Contains required fields for VAT setup with German compliance.
     */
    @Serializable
    data class CreateVatRequestDto(
        /** VAT unique identifier */
        val vatId: String,
        /** VAT classification identifier */
        val vatClass: String,
        /** VAT rate as decimal (e.g., 0.19 for 19%) */
        val vatRate: Double,
    ) {
        /**
         * Validates the create request data.
         *
         * Ensures all required fields are present and values
         * meet German VAT requirements and business rules.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(vatClass.isNotBlank()) { "VAT class is required and cannot be blank" }
            require(vatClass.length <= 50) { "VAT class cannot exceed 50 characters" }
            require(vatClass.matches(Regex("^[A-Z_]+\$"))) {
                "VAT class must contain only uppercase letters and underscores"
            }

            require(vatRate >= 0.0) { "VAT rate cannot be negative" }
            require(vatRate <= 1.0) { "VAT rate cannot exceed 100% (1.0)" }
            require(vatRate == round(vatRate * 10000) / 10000) {
                "VAT rate cannot have more than 4 decimal places"
            }

            // German VAT compliance checks
            validateGermanVatCompliance()
        }

        /**
         * Validates German VAT compliance rules.
         *
         * Ensures VAT rates align with common German tax rates
         * and classification standards.
         */
        private fun validateGermanVatCompliance() {
            val commonGermanRates = setOf(0.0, 0.07, 0.19) // 0%, 7%, 19%

            when (vatClass.uppercase()) {
                "A" ->
                    require(vatRate == 0.19) {
                        "Standard German VAT rate must be 19% (0.19)"
                    }

                "B" ->
                    require(vatRate == 0.07) {
                        "Reduced German VAT rate must be 7% (0.07)"
                    }

                "C" ->
                    require(vatRate == 0.0) {
                        "Zero VAT rate must be 0% (0.0)"
                    }
            }
        }
    }

    /**
     * Request DTO for updating existing VAT rates.
     *
     * Used for API requests when updating VAT information.
     * Fields are optional to support partial updates.
     */
    @Serializable
    data class UpdateVatRequestDto(
        /** Updated VAT classification */
        val vatClass: String? = null,
        /** Updated VAT rate */
        val vatRate: Double? = null,
    ) {
        /**
         * Validates the update request data.
         *
         * Ensures provided fields meet format requirements.
         * Null fields are ignored for partial updates.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            vatClass?.let { vClass ->
                require(vClass.isNotBlank()) { "VAT class cannot be blank if provided" }
                require(vClass.length <= 50) { "VAT class cannot exceed 50 characters" }
                require(vClass.matches(Regex("^[A-Z_]+\$"))) {
                    "VAT class must contain only uppercase letters and underscores"
                }
            }

            vatRate?.let { rate ->
                require(rate >= 0.0) { "VAT rate cannot be negative" }
                require(rate <= 1.0) { "VAT rate cannot exceed 100% (1.0)" }
                require(rate == round(rate * 10000) / 10000) {
                    "VAT rate cannot have more than 4 decimal places"
                }
            }
        }

        /**
         * Checks if the update request contains any actual changes.
         *
         * Determines whether the request has any non-null fields
         * to avoid unnecessary database operations.
         *
         * @return true if at least one field is provided for update
         */
        fun hasChanges(): Boolean = vatClass != null || vatRate != null
    }

    /**
     * Summary DTO for VAT list responses.
     *
     * Contains essential VAT information for dropdown lists
     * and selection interfaces without full detail.
     */
    @Serializable
    data class VatSummaryDto(
        /** VAT unique identifier */
        val vatId: String,
        /** VAT classification for display */
        val vatClass: String,
        /** VAT rate formatted as percentage */
        val vatPercentage: String,
        /** Whether this is a standard German VAT rate */
        val isStandardRate: Boolean = false,
    ) {
        companion object
    }
}
