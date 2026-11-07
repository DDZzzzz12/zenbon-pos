package org.example.project.admin.features.product.presentation

import org.example.project.core.domain.dto.product.ProductDto

/** The catalog totals shown under the Produkte headline: "53 Produkte · 49 aktiv · 4 inaktiv". */
data class ProductSummary(
    val total: Int,
    val active: Int,
    val inactive: Int,
) {
    companion object {
        fun of(products: List<ProductDto.ProductResponseDto>): ProductSummary {
            val active = products.count { it.isActive }
            return ProductSummary(total = products.size, active = active, inactive = products.size - active)
        }
    }
}
