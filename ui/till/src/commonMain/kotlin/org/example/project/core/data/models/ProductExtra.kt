/**
 * Product extra data model.
 *
 * Represents a product extra/addon entity for local storage and offline functionality,
 * following clean architecture principles with no external dependencies.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.models

import kotlinx.serialization.Serializable
import org.example.project.core.domain.dto.product.ProductExtrasDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.money.Decimal

/**
 * Product extra data model
 */
@Serializable
data class ProductExtra(
    val id: Long,
    val name: String,
    val enName: String? = null,
    val chName: String? = null,
    val description: String?,
    val price: Decimal,
    val isActive: Boolean,
    val linkedProductId: Long? = null,
) {
    companion object {
        /**
         * Converts SQLDelight database row to ProductExtra model.
         *
         * @param dbExtra SQLDelight database row
         * @return ProductExtra domain model
         */
        fun fromDatabase(dbExtra: org.example.project.database.Local_product_extras): ProductExtra =
            ProductExtra(
                id = dbExtra.id,
                name = dbExtra.name,
                enName = dbExtra.en_name,
                chName = dbExtra.ch_name,
                description = dbExtra.description,
                price = dbExtra.price,
                isActive = dbExtra.is_active == 1L,
                linkedProductId = dbExtra.linked_product_id,
            )

        /**
         * Converts ProductExtrasDto.ProductExtrasResponseDto to ProductExtra model.
         *
         * Used when syncing data from server API to local storage.
         *
         * @param dto API response DTO
         * @return ProductExtra domain model
         */
        fun fromDto(dto: ProductExtrasDto.ProductExtrasResponseDto): ProductExtra =
            ProductExtra(
                id = dto.id.toLong(),
                name = dto.name,
                enName = dto.en,
                chName = dto.ch,
                description = dto.description,
                price = Decimal.parse(dto.price),
                isActive = dto.isActive,
                linkedProductId = null, // populated when server DTO includes linked_product_id
            )
    }

    fun getName(language: Language): String =
        when (language) {
            Language.GERMAN -> name
            Language.CHINESE -> chName ?: name
            Language.ENGLISH -> enName ?: name
        }
}
