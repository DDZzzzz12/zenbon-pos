/**
 * Product entity to DTO mapping functions.
 *
 * Provides conversion methods for Product entities to their corresponding DTOs
 * for API responses and data transfer operations.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.product.mapper

import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.features.product.domain.models.Product
import org.example.project.utils.formatter.formatDecimal

/**
 * Converts Product entity to ProductResponseDto.
 *
 * [currency] is the shop's configured ISO 4217 code. It is a required parameter, not a default,
 * so it can only come from the service that resolved it once for the whole request — a mapper
 * that silently fell back to "EUR" would be indistinguishable from a correct one.
 */
fun Product.toResponse(currency: String): ProductDto.ProductResponseDto =
    ProductDto.ProductResponseDto(
        id = this.id.value,
        productNumber = this.productNumber,
        name = this.name,
        en = this.enName,
        ch = this.chName,
        description = this.description,
        price = formatDecimal(this.price),
        categoryId = this.category.id.value,
        categoryName = this.category.name,
        productType = this.productType.name,
        isActive = this.isActive,
        allowCustomPrice = this.allowCustomPrice,
        imageUrl = this.imageUrl,
        isDeposit = this.isDeposit,
        isDepositReturn = this.isDepositReturn,
        pfandTypeId = this.pfandType?.id?.value,
        pfandTypeName = this.pfandType?.name,
        currency = currency,
    )

/**
 * Extension function to create ProductResponseDto.fromEntity() functionality.
 */
fun ProductDto.ProductResponseDto.Companion.fromEntity(
    entity: Product,
    currency: String,
): ProductDto.ProductResponseDto = entity.toResponse(currency)
