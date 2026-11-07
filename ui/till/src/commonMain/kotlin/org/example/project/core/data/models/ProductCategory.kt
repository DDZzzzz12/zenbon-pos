/**
 * Product category data model.
 *
 * Represents a product category entity for local storage and offline functionality,
 * following clean architecture principles with no external dependencies.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.models

import kotlinx.serialization.Serializable
import org.example.project.core.domain.dto.product.ProductCategoryDto
import org.example.project.core.domain.enums.setup.Language

/**
 * Product category data model
 */
@Serializable
data class ProductCategory(
    val id: Long,
    val name: String,
    val enName: String? = null,
    val chName: String? = null,
    val description: String?,
    val isActive: Boolean,
    val sortPosition: Double,
    val updatedAt: String,
    val visibility: String = "EVERYWHERE",
) {
    companion object {
        /**
         * Converts SQLDelight database row to ProductCategory model.
         *
         * @param dbCategory SQLDelight database row
         * @return ProductCategory domain model
         */
        fun fromDatabase(dbCategory: org.example.project.database.Local_product_categories): ProductCategory =
            ProductCategory(
                id = dbCategory.id,
                name = dbCategory.name,
                enName = dbCategory.en_name,
                chName = dbCategory.ch_name,
                description = dbCategory.description,
                isActive = dbCategory.is_active == 1L,
                sortPosition = dbCategory.sort_position,
                updatedAt = "", // Database schema doesn't track updatedAt for local storage
                visibility = "EVERYWHERE", // local DB doesn't track visibility
            )

        /**
         * Converts ProductCategoryDto.ProductCategoryResponseDto to ProductCategory model.
         *
         * Used when syncing data from server API to local storage.
         *
         * @param dto API response DTO
         * @return ProductCategory domain model
         */
        fun fromDto(dto: ProductCategoryDto.ProductCategoryResponseDto): ProductCategory =
            ProductCategory(
                id = dto.id.toLong(),
                name = dto.name,
                enName = dto.en,
                chName = dto.ch,
                description = dto.description,
                isActive = dto.isActive,
                sortPosition = dto.sortPosition,
                updatedAt = dto.updatedAt,
                visibility = dto.visibility,
            )
    }

    fun getName(language: Language): String =
        when (language) {
            Language.GERMAN -> name
            Language.CHINESE -> chName ?: name
            Language.ENGLISH -> enName ?: name
        }
}
