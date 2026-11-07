/**
 * Product Category entity to DTO mapping functions.
 *
 * Provides conversion methods for ProductCategory entities to their corresponding DTOs
 * for API responses and data transfer operations.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.product.mapper

import org.example.project.core.domain.dto.product.ProductCategoryDto
import org.example.project.features.product.domain.models.ProductCategory

/**
 * Converts ProductCategory entity to ProductCategoryResponseDto.
 */
fun ProductCategory.toResponse(): ProductCategoryDto.ProductCategoryResponseDto =
    ProductCategoryDto.ProductCategoryResponseDto(
        id = this.id.value,
        name = this.name,
        en = this.enName,
        ch = this.chName,
        description = this.description,
        isActive = this.isActive,
        sortPosition = this.sortPosition.toDouble(),
        updatedAt = this.updatedAt.toString(),
        visibility = this.visibility,
    )

/**
 * Extension function to create ProductCategoryResponseDto.fromEntity() functionality.
 */
fun ProductCategoryDto.ProductCategoryResponseDto.Companion.fromEntity(entity: ProductCategory): ProductCategoryDto.ProductCategoryResponseDto =
    entity.toResponse()
