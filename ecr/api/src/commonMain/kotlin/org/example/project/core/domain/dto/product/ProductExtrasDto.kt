package org.example.project.core.domain.dto.product

import kotlinx.serialization.Serializable
import org.example.project.money.Decimal
import org.example.project.money.MoneyException

/**
 * Data Transfer Objects for ProductExtras operations.
 *
 * Container object for all ProductExtras-related DTOs including request validation,
 * response formatting, and data transformation utilities. Follows the standard
 * object container pattern for organized DTO management.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * Container object for all ProductExtras-related DTOs.
 *
 * Organizes DTOs by functionality: requests, responses, and utilities
 * to maintain clear separation of concerns and consistent API structure.
 */
object ProductExtrasDto {
    @Serializable
    data class ProductExtrasDto(
        val id: Int,
        val name: String,
        val price: String,
    )

    /**
     * Response DTO for ProductExtras data.
     *
     * Used for API responses containing product extras information
     * including pricing and category associations.
     */
    @Serializable
    data class ProductExtrasResponseDto(
        val id: Int,
        val name: String,
        val en: String? = null,
        val ch: String? = null,
        val description: String? = null,
        val price: String, // BigDecimal serialized as String
        val isActive: Boolean,
        /**
         * ISO 4217 code [price] is denominated in. Supplied by the server from shop config
         * (`ConfigKey.DEFAULT_CURRENCY`). The default only keeps non-server construction sites
         * compiling; the server mapper sets it explicitly.
         */
        val currency: String = "EUR",
    )

    /**
     * Request DTO for creating new ProductExtras.
     *
     * Used for API requests to create new product extras
     * with validation and business rules.
     */
    @Serializable
    data class CreateProductExtrasRequestDto(
        val name: String,
        val en: String? = null,
        val ch: String? = null,
        val description: String? = null,
        val price: String, // BigDecimal as String for JSON serialization
        val isActive: Boolean = true,
    ) {
        /**
         * Validates the create product extras request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(name.isNotBlank()) { "Product extras name is required and cannot be blank" }
            require(name.length <= 255) { "Product extras name must not exceed 255 characters" }

            // Validate price format: exact decimal, no Double, no cents.
            val parsed =
                try {
                    Decimal.parse(price)
                } catch (e: MoneyException) {
                    throw IllegalArgumentException("Price must be a valid decimal number")
                }
            require(!parsed.isNegative) { "Price cannot be negative" }
        }
    }

    /**
     * Request DTO for updating existing ProductExtras.
     *
     * Used for API requests to update existing product extras
     * with validation and business rules.
     */
    @Serializable
    data class UpdateProductExtrasRequestDto(
        val name: String,
        val en: String? = null,
        val ch: String? = null,
        val description: String? = null,
        val price: String, // BigDecimal as String for JSON serialization
        val isActive: Boolean,
    ) {
        /**
         * Validates the update product extras request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(name.isNotBlank()) { "Product extras name is required and cannot be blank" }
            require(name.length <= 255) { "Product extras name must not exceed 255 characters" }

            // Validate price format: exact decimal, no Double, no cents.
            val parsed =
                try {
                    Decimal.parse(price)
                } catch (e: MoneyException) {
                    throw IllegalArgumentException("Price must be a valid decimal number")
                }
            require(!parsed.isNegative) { "Price cannot be negative" }
        }
    }
}
