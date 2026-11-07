package org.example.project.features.product.data.repository

import org.example.project.core.domain.dto.product.FavouriteProductDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.order.database.tables.OrderItemTable
import org.example.project.features.product.database.tables.FavouriteProductTable
import org.example.project.features.product.domain.repository.FavouriteProductRepository
import org.example.project.utils.formatter.toDecimalString
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

class FavouriteProductRepositoryImpl : FavouriteProductRepository {
    override fun calculateAndPopulateFavourites(
        limit: Int,
        periodDays: Int,
    ) {
        // 1. Calculate top ordered products
        val topProducts =
            OrderItemTable
                .select(OrderItemTable.productId, OrderItemTable.productId.count())
                .where {
                    OrderItemTable.itemStatus neq ItemStatus.CANCELLED
                }.groupBy(OrderItemTable.productId)
                .orderBy(OrderItemTable.productId.count(), SortOrder.DESC)
                .limit(limit)
                .map {
                    it[OrderItemTable.productId]
                }

        // 2. Upsert top products into fixed slots (1 to limit)
        topProducts.forEachIndexed { index, productId ->
            val slotId = (index + 1).toLong()
            FavouriteProductTable.upsert {
                it[id] = slotId
                it[product] = productId
                it[sortingPosition] = index
            }
        }

        // 3. Clear any remaining slots up to the limit if fewer products were found
        if (topProducts.size < limit) {
            FavouriteProductTable.deleteWhere {
                FavouriteProductTable.id greater topProducts.size.toLong() and (FavouriteProductTable.id lessEq limit.toLong())
            }
        }
    }

    override fun getFavourites(): List<Long> =
        FavouriteProductTable
            .selectAll()
            .orderBy(FavouriteProductTable.sortingPosition, SortOrder.ASC)
            .map { it[FavouriteProductTable.product].value }

    override fun getFavouritesWithAnalytics(): List<FavouriteProductDto.FavouriteProductAnalyticsDto> =
        FavouriteProductTable
            .selectAll()
            .orderBy(FavouriteProductTable.sortingPosition, SortOrder.ASC)
            .map { row ->
                FavouriteProductDto.FavouriteProductAnalyticsDto(
                    productId = row[FavouriteProductTable.product].value,
                    productName = "Product ${row[FavouriteProductTable.product].value}",
                    orderCount = row[FavouriteProductTable.orderCount],
                    totalRevenue = row[FavouriteProductTable.totalRevenue].toDecimalString(),
                    trend = FavouriteProductDto.Trend.FLAT,
                    trendPercentage = 0.0,
                    periodDays = row[FavouriteProductTable.periodDays],
                    calculatedAt = row[FavouriteProductTable.calculatedAt],
                )
            }
}
