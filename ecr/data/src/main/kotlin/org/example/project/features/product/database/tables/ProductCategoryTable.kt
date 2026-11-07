/**
 * Exposed table definition for product categories.
 *
 * Defines the database schema structure for product categories with proper
 * column types, constraints, and indexing for optimal performance.
 * Supports product organization and menu management functionality.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.product.database.tables

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Exposed table definition for product_category table.
 *
 * Manages product categories for menu organization and inventory management.
 * Includes sorting capabilities and relationship management for extras and printer locations.
 */
object ProductCategoryTable : IntIdTable("product_category", "id") {
    /** Category name */
    val name = varchar("name", 100).uniqueIndex()

    val enName = varchar("en_name", 100).nullable()

    val chName = varchar("ch_name", 100).nullable()

    /** Category description */
    val description = text("description").nullable()

    /** Whether category is active */
    val isActive = bool("is_active").default(true)

    /** Sort position for category ordering (lower numbers appear first) */
    val sortPosition = decimal("sort_position", 8, 5)

    /** Record update timestamp */
    val updatedAt = datetime("updated_at")
    val visibility = varchar("visibility", 50).default("EVERYWHERE")
}
