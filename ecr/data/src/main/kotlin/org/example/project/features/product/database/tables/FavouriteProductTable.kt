package org.example.project.features.product.database.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import java.math.BigDecimal

/**
 * Table for storing favorite products with analytics data.
 * Stores top ordered products based on order history with trend information.
 */
object FavouriteProductTable : LongIdTable("favourite_product") {
    val product = reference("product_id", ProductTable)
    val sortingPosition = integer("sorting_position")
    val orderCount = long("order_count").default(0L)
    val totalRevenue = decimal("total_revenue", precision = 19, scale = 4).default(BigDecimal.ZERO)
    val lastPeriodCount = long("last_period_count").default(0L)
    val lastPeriodRevenue = decimal("last_period_revenue", precision = 19, scale = 4).default(BigDecimal.ZERO)
    val calculatedAt = varchar("calculated_at", 50).nullable()
    val periodDays = integer("period_days").default(30)
}
