/**
 * Exposed table definition for product extras.
 *
 * Defines the database schema structure for product extras (add-ons) with proper
 * column types and constraints. Supports product customization and upselling
 * functionality in the point-of-sale system.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.product.database.tables

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Exposed table definition for product_extras table.
 *
 * Manages additional options/add-ons that can be associated with products
 * or product categories for enhanced ordering capabilities.
 */
object ProductExtrasTable : IntIdTable("product_extras", "id") {
    /** Extra item name/description */
    val name = varchar("name", 255)

    val enName = varchar("en_name", 255).nullable()

    val chName = varchar("ch_name", 255).nullable()

    /** Additional price for the extra item */
    val price = decimal("price", precision = 19, scale = 4)

    /** Whether extra is active */
    val isActive = bool("is_active").default(true)

    /** Record update timestamp */
    val updatedAt = datetime("updated_at")

    /** Optional link to a real product when this option IS a sellable product (identity only). */
    val linkedProductId = reference("linked_product_id", ProductTable).nullable()
}
