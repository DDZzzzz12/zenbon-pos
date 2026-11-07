package org.example.project.core.domain.dto.product

import kotlinx.serialization.Serializable

/**
 * Container object for favorite product DTOs.
 * Provides analytics data for top ordered products including trend information.
 */
object FavouriteProductDto {
    /**
     * Trend direction for favorite product analytics.
     */
    @Serializable
    enum class Trend {
        UP,
        DOWN,
        FLAT,
    }

    /**
     * Analytics data for a single favorite product.
     */
    @Serializable
    data class FavouriteProductAnalyticsDto(
        val productId: Long,
        val productName: String,
        val orderCount: Long,
        val totalRevenue: String,
        val trend: Trend,
        val trendPercentage: Double,
        val periodDays: Int,
        val calculatedAt: String?,
    )

    /**
     * Response containing list of favorite products with analytics.
     */
    @Serializable
    data class FavouriteProductListResponseDto(
        val products: List<FavouriteProductAnalyticsDto>,
        val periodDays: Int,
        val calculatedAt: String?,
    )

    /**
     * Request DTO for calculating favorite products.
     */
    @Serializable
    data class CalculateFavouritesRequestDto(
        val limit: Int = 20,
        val periodDays: Int = 30,
    )
}
