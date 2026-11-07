/**
 * Exposed DAO entity for Product.
 *
 * Provides object-relational mapping for products with proper relationship
 * management and type-safe database operations. Supports product management,
 * pricing, and categorization for the point-of-sale system.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.product.domain.models

import org.example.project.features.pfand.domain.models.PfandType
import org.example.project.features.product.database.tables.ProductTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Product entity for menu management and inventory tracking.
 *
 * Manages individual products including pricing, categorization, and status.
 * Central entity for menu management and inventory tracking.
 */
class Product(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<Product>(ProductTable)

    /** Unique product identifier for business operations */
    var productNumber by ProductTable.productNumber

    /** Product name */
    var name by ProductTable.name

    var enName by ProductTable.enName

    var chName by ProductTable.chName

    /** Product description */
    var description by ProductTable.description

    /** Product price with high precision for accurate financial calculations */
    var price by ProductTable.price

    /** Reference to product category */
    var category by ProductCategory referencedOn ProductTable.categoryId

    /** Product type classification (FOOD, DRINKS, etc.) */
    var productType by ProductTable.productType

    var code by ProductTable.code

    /** Product availability status */
    var isActive by ProductTable.isActive

    /** Record update timestamp */
    var updatedAt by ProductTable.updatedAt

    /** Deposit (Pfand) flag - product represents a deposit charge */
    var isDeposit by ProductTable.isDeposit

    /** Deposit return (Pfandrückgabe) flag - product represents a deposit refund */
    var isDepositReturn by ProductTable.isDepositReturn

    /** Whether this product allows custom pricing by privileged users */
    var allowCustomPrice by ProductTable.allowCustomPrice
    var imageUrl by ProductTable.imageUrl

    /** Linked Pfand type (deposit link on a drink, or self-link on a backing product). */
    var pfandType by PfandType optionalReferencedOn ProductTable.pfandTypeId
}
