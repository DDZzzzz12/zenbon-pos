package org.example.project.features.product.data.service

import org.example.project.core.domain.dto.product.FavouriteProductDto
import org.example.project.features.product.domain.repository.FavouriteProductRepository
import org.example.project.features.product.domain.service.FavouriteProductService
import org.example.project.utils.dbQuery
import org.slf4j.LoggerFactory

/**
 * Implementation of favorite product analytics service.
 *
 * Migrated to throws-style in Stage B Batch 10. Throws PosException on errors;
 * the global StatusPages handler converts to application/problem+json.
 */
class FavouriteProductServiceImpl(
    private val favouriteProductRepository: FavouriteProductRepository,
) : FavouriteProductService {
    private val logger = LoggerFactory.getLogger(FavouriteProductServiceImpl::class.java)

    override suspend fun updateFavourites(
        limit: Int,
        periodDays: Int,
    ) {
        logger.info("updateFavourites: recalculating favourites with limit={}, periodDays={}", limit, periodDays)
        dbQuery {
            favouriteProductRepository.calculateAndPopulateFavourites(limit, periodDays)
        }
        logger.info("updateFavourites: favourites recalculated successfully")
    }

    override suspend fun getFavourites(): List<Long> =
        dbQuery {
            favouriteProductRepository.getFavourites()
        }

    override suspend fun getFavouritesWithAnalytics(periodDays: Int): FavouriteProductDto.FavouriteProductListResponseDto =
        dbQuery {
            val products = favouriteProductRepository.getFavouritesWithAnalytics()
            val calculatedAt = products.firstOrNull()?.calculatedAt
            FavouriteProductDto.FavouriteProductListResponseDto(
                products = products,
                periodDays = periodDays,
                calculatedAt = calculatedAt,
            )
        }
}
