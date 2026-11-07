package org.example.project.admin.features.product.presentation

import org.example.project.core.domain.dto.product.ProductChoiceGroupDto
import org.example.project.core.domain.dto.product.ProductDto

/**
 * The secondary facts of a product row: whether a Pfand type is linked and how many choice groups
 * are attached. Both are optional; a product with neither has no meta line.
 */
data class ProductMeta(
    val hasPfand: Boolean,
    val groupCount: Int,
) {
    val isEmpty: Boolean get() = !hasPfand && groupCount == 0

    companion object {
        val NONE = ProductMeta(hasPfand = false, groupCount = 0)

        /** Attached choice groups per product id, counted once for the whole list. */
        fun groupCounts(links: List<ProductChoiceGroupDto.ResponseDto>): Map<Long, Int> = links.groupingBy { it.productId }.eachCount()

        fun of(
            product: ProductDto.ProductResponseDto,
            groupCounts: Map<Long, Int>,
        ): ProductMeta = ProductMeta(hasPfand = product.pfandTypeId != null, groupCount = groupCounts[product.id] ?: 0)
    }
}
