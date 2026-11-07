/**
 * Product data model.
 *
 * Represents a product entity for local storage and offline functionality,
 * following clean architecture principles with no external dependencies.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.models

import kotlinx.serialization.Serializable
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.utils.resolveServerImageUrl
import org.example.project.database.Local_products
import org.example.project.money.Decimal

/**
 * Product data model
 */
@Serializable
data class Product(
    val id: Long,
    val productNumber: String,
    val name: String,
    val enName: String? = null,
    val chName: String? = null,
    val description: String?,
    val price: Decimal,
    val productType: String,
    val categoryId: Long,
    val isActive: Boolean,
    val allowCustomPrice: Boolean = false,
    val imageUrl: String? = null,
) {
    companion object {
        /**
         * Converts SQLDelight database row to Product model.
         *
         * @param dbProduct SQLDelight database row
         * @return Product domain model
         */
        fun fromDatabase(dbProduct: Local_products): Product =
            Product(
                id = dbProduct.id,
                productNumber = dbProduct.product_number,
                name = dbProduct.name,
                enName = dbProduct.en_name,
                chName = dbProduct.ch_name,
                description = dbProduct.description,
                price = dbProduct.price,
                productType = dbProduct.product_type,
                categoryId = dbProduct.category_id,
                isActive = dbProduct.is_active == 1L,
                allowCustomPrice = dbProduct.allow_custom_price == 1L,
                imageUrl = dbProduct.image_url,
            )

        /**
         * Converts ProductDto.ProductResponseDto to Product model.
         *
         * Used when syncing data from server API to local storage.
         *
         * The price is parsed straight from the server's string. The previous route parsed it
         * through `Double` then `Long` minor units (with a `!!`) — which pushed every price through
         * a float and pinned the scale at 2, discarding the sub-cent precision the server holds in
         * DECIMAL(19,4). A malformed price now raises a typed `DecimalParseException` rather than
         * a NullPointerException from the `!!`.
         *
         * @param dto API response DTO
         * @return Product domain model
         */
        fun fromDto(dto: ProductDto.ProductResponseDto): Product =
            Product(
                id = dto.id,
                productNumber = dto.productNumber,
                name = dto.name,
                enName = dto.en,
                chName = dto.ch,
                description = dto.description,
                price = Decimal.parse(dto.price),
                productType = dto.productType,
                categoryId = dto.categoryId.toLong(),
                isActive = dto.isActive,
                allowCustomPrice = dto.allowCustomPrice,
                imageUrl = dto.imageUrl,
            )
    }

    fun getName(language: Language): String =
        when (language) {
            Language.GERMAN -> name
            Language.ENGLISH -> enName ?: name
            Language.CHINESE -> chName ?: name
        }

    /**
     * Resolves the relative imageUrl to a full URL using the API base URL.
     *
     * @param baseUrl API base URL (e.g. "http://localhost:8080/")
     * @return Full image URL, or null if no image
     */
    fun resolveImageUrl(baseUrl: String?): String? = resolveServerImageUrl(baseUrl, imageUrl)
}
