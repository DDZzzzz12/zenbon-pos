package org.example.project.core.domain.dto.product

import kotlinx.serialization.Serializable
import org.example.project.core.utils.DsfinvkFieldLimits

/**
 * Data Transfer Objects for ProductCategory operations.
 *
 * Container object for all ProductCategory-related DTOs including request validation,
 * response formatting, and data transformation utilities. Follows the standard
 * object container pattern for organized DTO management.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * Container object for all ProductCategory-related DTOs.
 *
 * Organizes DTOs by functionality: requests, responses, and utilities
 * to maintain clear separation of concerns and consistent API structure.
 */
object ProductCategoryDto {
    /**
     * Response DTO for ProductCategory data.
     *
     * Used for API responses containing product category information
     * including associated product count for business insights.
     */
    @Serializable
    data class ProductCategoryResponseDto(
        val id: Int,
        val name: String,
        val en: String? = null,
        val ch: String? = null,
        val description: String?,
        val isActive: Boolean,
        val sortPosition: Double,
        val updatedAt: String,
        val visibility: String = "EVERYWHERE",
    )

    /**
     * Request DTO for creating new ProductCategory.
     *
     * Used for API requests to create new product categories
     * with validation and business rules.
     */
    @Serializable
    data class CreateProductCategoryRequestDto(
        val name: String,
        val en: String? = null,
        val ch: String? = null,
        val description: String?,
        val isActive: Boolean = true,
        val sortOrder: Double = 0.0,
        val visibility: String = "EVERYWHERE",
    ) {
        /**
         * Validates the create product category request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(name.isNotBlank()) { "Product category name is required and cannot be blank" }
            require(name.length <= 255) { "Product category name must not exceed 255 characters" }
            // M1: the category name is exported as lines.csv WARENGR (max 50).
            DsfinvkFieldLimits.requireMaxLength(name, DsfinvkFieldLimits.WARENGR, "Category name (WARENGR)")

            description?.let { desc ->
                require(desc.length <= 1000) { "Description must not exceed 1000 characters" }
            }
        }
    }

    /**
     * Request DTO for updating existing ProductCategory.
     *
     * Used for API requests to update existing product categories
     * with validation and business rules.
     */
    @Serializable
    data class UpdateProductCategoryRequestDto(
        val name: String,
        val en: String? = null,
        val ch: String? = null,
        val description: String?,
        val isActive: Boolean,
        val visibility: String? = null,
    ) {
        /**
         * Validates the update product category request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(name.isNotBlank()) { "Product category name is required and cannot be blank" }
            require(name.length <= 255) { "Product category name must not exceed 255 characters" }
            // M1: the category name is exported as lines.csv WARENGR (max 50).
            DsfinvkFieldLimits.requireMaxLength(name, DsfinvkFieldLimits.WARENGR, "Category name (WARENGR)")

            description?.let { desc ->
                require(desc.length <= 1000) { "Description must not exceed 1000 characters" }
            }
        }
    }

    /**
     * Request DTO for updating category sort position.
     *
     * Used for bulk updating category positions after drag-and-drop reordering.
     */
    @Serializable
    data class UpdateProductCategoryPositionRequestDto(
        val categoryId: Int,
        val sortOrder: Double,
    )

    /**
     * Request DTO for bulk updating multiple category positions.
     */
    @Serializable
    data class BulkUpdateCategoryPositionsRequestDto(
        val updates: List<UpdateProductCategoryPositionRequestDto>,
    )
}
