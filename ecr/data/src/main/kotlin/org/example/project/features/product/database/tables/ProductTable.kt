/**
 * Exposed table definition for products.
 *
 * Defines the database schema structure for products with proper column types,
 * constraints, and relationships. Supports product management, pricing, and
 * categorization for the point-of-sale system.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.product.database.tables

import org.example.project.core.domain.enums.product.ProductType
import org.example.project.features.pfand.database.tables.PfandTypeTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Exposed table definition for product table.
 *
 * Manages individual products including pricing, categorization, and status.
 * Central table for menu management and inventory tracking.
 */
object ProductTable : LongIdTable("product", "id") {
    /** Unique product identifier for business operations */
    val productNumber = varchar("product_number", 100).uniqueIndex()

    /** Product name */
    val name = varchar("name", 100)

    val enName = varchar("en_name", 100).nullable()

    val chName = varchar("ch_name", 100).nullable()

    /** Product description */
    val description = text("description").nullable()

    /** Product price with high precision for accurate financial calculations */
    val price = decimal("price", precision = 19, scale = 4)

    /** Reference to product category */
    val categoryId = reference("category_id", ProductCategoryTable)

    /** Product type classification (FOOD, DRINKS, etc.) */
    val productType = enumerationByName("product_type", 50, ProductType::class)

    val code = varchar("product_code", 100).uniqueIndex().nullable()

    /** Product availability status */
    val isActive = bool("is_active").default(true)

    /** Record update timestamp */
    val updatedAt = datetime("updated_at")

    /** Deposit (Pfand) flag - product represents a deposit charge */
    val isDeposit = bool("is_deposit").default(false)

    /** Deposit return (Pfandrückgabe) flag - product represents a deposit refund */
    val isDepositReturn = bool("is_deposit_return").default(false)

    /** Whether this product allows custom pricing by privileged users */
    val allowCustomPrice = bool("allow_custom_price").default(false)
    val imageUrl = varchar("image_url", 500).nullable()

    /**
     * Deposit link. On a drink: "auto-attach this type's deposit". On a backing
     * product (isDeposit/isDepositReturn): "I am this type's article".
     */
    val pfandTypeId = optReference("pfand_type_id", PfandTypeTable)

    init {
        // Menu / POS grid listing (active products of a category) — kept in sync with
        // Flyway V86 (matching index name so fresh and existing installs converge).
        index("idx_product_category_active", false, categoryId, isActive)
    }
}
