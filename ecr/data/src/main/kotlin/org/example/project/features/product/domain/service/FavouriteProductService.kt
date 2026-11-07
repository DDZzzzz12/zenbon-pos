package org.example.project.features.product.domain.service

import org.example.project.core.domain.dto.product.FavouriteProductDto

/**
 * Service interface for favorite product analytics operations.
 *
 * Migrated to throws-style in Stage B Batch 10.
 */
interface FavouriteProductService {
    /**
     * Recalculates and updates the favourite products list based on order history.
     *
     * @param limit Number of top products to keep as favourites (default: 20)
     * @param periodDays Number of days to look back for analytics (default: 30)
     */
    suspend fun updateFavourites(
        limit: Int = 20,
        periodDays: Int = 30,
    )

    /**
     * Retrieves current favourite product IDs ordered by ranking.
     */
    suspend fun getFavourites(): List<Long>

    /**
     * Retrieves favourite products with full analytics data.
     *
     * @param periodDays Number of days used in the calculation
     */
    suspend fun getFavouritesWithAnalytics(periodDays: Int = 30): FavouriteProductDto.FavouriteProductListResponseDto
}
