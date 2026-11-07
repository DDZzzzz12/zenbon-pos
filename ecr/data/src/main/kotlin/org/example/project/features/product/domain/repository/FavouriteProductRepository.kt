package org.example.project.features.product.domain.repository

import org.example.project.core.domain.dto.product.FavouriteProductDto

/**
 * Repository interface for favorite product analytics operations.
 * Returns plain types; exceptions propagate naturally to the service layer.
 */
interface FavouriteProductRepository {
    /**
     * Calculate and populate favorite products based on order history.
     * @param limit Maximum number of products to include
     * @param periodDays Number of days to look back for current period
     */
    fun calculateAndPopulateFavourites(
        limit: Int = 20,
        periodDays: Int = 30,
    )

    /**
     * Get list of favorite product IDs ordered by ranking.
     */
    fun getFavourites(): List<Long>

    /**
     * Get favorite products with full analytics data.
     */
    fun getFavouritesWithAnalytics(): List<FavouriteProductDto.FavouriteProductAnalyticsDto>
}
