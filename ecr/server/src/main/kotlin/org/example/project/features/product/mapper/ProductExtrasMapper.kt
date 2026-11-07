/**
 * Product Extras entity to DTO mapping functions.
 *
 * Provides conversion methods for ProductExtras entities to their corresponding DTOs
 * for API responses and data transfer operations.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.product.mapper

import org.example.project.core.domain.dto.product.ProductExtrasDto
import org.example.project.features.product.domain.models.ProductExtras
import org.example.project.utils.formatter.formatDecimal

/**
 * Converts ProductExtras entity to ProductExtrasResponseDto.
 *
 * [currency] is the shop's configured ISO 4217 code, resolved once per request by the calling
 * service. Required rather than defaulted, so a mapper can never silently fall back to "EUR".
 */
fun ProductExtras.toResponse(currency: String): ProductExtrasDto.ProductExtrasResponseDto =
    ProductExtrasDto.ProductExtrasResponseDto(
        id = this.id.value,
        name = this.name,
        en = this.enName,
        ch = this.chName,
        price = formatDecimal(this.price),
        isActive = this.isActive,
        currency = currency,
    )

fun ProductExtras.toDto(): ProductExtrasDto.ProductExtrasDto =
    ProductExtrasDto.ProductExtrasDto(
        id = this.id.value,
        name = this.name,
        price = formatDecimal(this.price),
    )

/**
 * Extension function to create ProductExtrasResponseDto.fromEntity() functionality.
 */
fun ProductExtrasDto.ProductExtrasResponseDto.Companion.fromEntity(
    entity: ProductExtras,
    currency: String,
): ProductExtrasDto.ProductExtrasResponseDto = entity.toResponse(currency)
