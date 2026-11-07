package org.example.project.core.data.models

import kotlinx.serialization.Serializable
import org.example.project.core.domain.dto.product.ProductExtrasAssignmentDto

/**
 * Product-extras assignment junction data model.
 *
 * Represents the many-to-many relationship between products and product extras.
 */
@Serializable
data class ProductExtrasAssignment(
    val productId: Long,
    val extrasId: Int,
) {
    companion object {
        /**
         * Converts SQLDelight database row to ProductExtrasAssignment model.
         *
         * @param row SQLDelight database row
         * @return ProductExtrasAssignment domain model
         */
        fun fromDatabase(row: org.example.project.database.LocalProductExtrasAssignment): ProductExtrasAssignment =
            ProductExtrasAssignment(
                productId = row.productId,
                extrasId = row.extrasId.toInt(),
            )

        /**
         * Converts a DTO response to ProductExtrasAssignment model.
         *
         * @param dto DTO from server response
         * @return ProductExtrasAssignment domain model
         */
        fun fromDto(dto: ProductExtrasAssignmentDto.ResponseDto): ProductExtrasAssignment =
            ProductExtrasAssignment(
                productId = dto.productId,
                extrasId = dto.extrasId,
            )
    }
}
