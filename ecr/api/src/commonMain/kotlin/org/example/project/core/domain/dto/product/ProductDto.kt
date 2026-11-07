package org.example.project.core.domain.dto.product

import kotlinx.serialization.Serializable

/**
 * Data Transfer Objects for Product operations.
 *
 * Container object for all Product-related DTOs including request validation,
 * response formatting, and data transformation utilities. Follows the standard
 * object container pattern for organized DTO management.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * Container object for all Product-related DTOs.
 *
 * Organizes DTOs by functionality: requests, responses, and utilities
 * to maintain clear separation of concerns and consistent API structure.
 */
object ProductDto {
    /**
     * Response DTO for Product data.
     *
     * Used for API responses containing product information
     * including category details and pricing information.
     */
    @Serializable
    data class ProductResponseDto(
        val id: Long,
        val productNumber: String,
        val name: String,
        val en: String? = null,
        val ch: String? = null,
        val description: String?,
        val price: String, // BigDecimal serialized as String
        val categoryId: Int,
        val categoryName: String,
        val productType: String,
        val isActive: Boolean,
        val allowCustomPrice: Boolean = false,
        val imageUrl: String? = null,
        val isDeposit: Boolean = false,
        val isDepositReturn: Boolean = false,
        val pfandTypeId: Long? = null,
        val pfandTypeName: String? = null,
        /**
         * ISO 4217 code the monetary fields of this response are denominated in.
         *
         * Supplied by the server from shop config (`ConfigKey.DEFAULT_CURRENCY`) so clients never
         * guess it from a constant. One field per response root — nested objects inherit it.
         * The default only keeps non-server construction sites compiling; every server mapper
         * sets it explicitly.
         */
        val currency: String = "EUR",
    )

    /**
     * Request DTO for creating a new Product.
     *
     * Used for API requests to create new products
     * with validation and business rules.
     */
    @Serializable
    data class CreateProductRequestDto(
        val productNumber: String,
        val name: String,
        val en: String? = null,
        val ch: String? = null,
        val description: String?,
        val price: String, // BigDecimal as String for JSON serialization
        val categoryId: Int,
        val productType: String,
        val isActive: Boolean = true,
        val allowCustomPrice: Boolean = false,
        val imageUrl: String? = null,
        val pfandTypeId: Long? = null,
    ) {
        /**
         * Validates the create product request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(productNumber.isNotBlank()) { "Product number is required and cannot be blank" }
            require(name.isNotBlank()) { "Product name is required and cannot be blank" }
            require(name.length <= 255) { "Product name must not exceed 255 characters" }
            require(categoryId > 0) { "Category ID must be positive" }

            // Validate price format
            try {
                val priceDouble = price.toDouble()
                require(priceDouble >= 0.0) { "Price cannot be negative" }
            } catch (e: NumberFormatException) {
                throw IllegalArgumentException("Price must be a valid decimal number")
            }
        }
    }

    /**
     * Request DTO for updating an existing Product.
     *
     * Used for API requests to update existing products
     * with validation and business rules.
     */
    @Serializable
    data class UpdateProductRequestDto(
        val productNumber: String,
        val name: String,
        val en: String? = null,
        val ch: String? = null,
        val description: String?,
        val price: String, // BigDecimal as String for JSON serialization
        val categoryId: Int,
        val productType: String,
        val isActive: Boolean,
        val allowCustomPrice: Boolean = false,
        val imageUrl: String? = null,
        val pfandTypeId: Long? = null,
    ) {
        /**
         * Validates the update product request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(productNumber.isNotBlank()) { "Product number is required and cannot be blank" }
            require(name.isNotBlank()) { "Product name is required and cannot be blank" }
            require(name.length <= 255) { "Product name must not exceed 255 characters" }
            require(categoryId > 0) { "Category ID must be positive" }

            // Validate price format
            try {
                val priceDouble = price.toDouble()
                require(priceDouble >= 0.0) { "Price cannot be negative" }
            } catch (e: NumberFormatException) {
                throw IllegalArgumentException("Price must be a valid decimal number")
            }
        }
    }

    /**
     * Response DTO for Product data.
     *
     * Used for API responses containing product information
     */
    @Serializable
    data class ImageUploadResponseDto(
        val imageUrl: String,
    )

    @Serializable
    data class OrderItemProductDto(
        val productId: Long,
        val productNumber: String,
        val productName: String,
        val en: String? = null,
        val ch: String? = null,
        val productType: String,
    )
}
