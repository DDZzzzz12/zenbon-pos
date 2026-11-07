/**
 * Exposed DAO entity for ProductExtras.
 *
 * Provides object-relational mapping for product extras/add-ons with proper
 * relationship management and type-safe database operations. Supports
 * product customization and upselling functionality.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.product.domain.models

import org.example.project.features.product.database.tables.ProductExtrasTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

/**
 * Product extras entity for managing add-ons and customization options.
 *
 * Manages additional options/add-ons that can be associated with products
 * or product categories for enhanced ordering capabilities.
 */
class ProductExtras(
    id: EntityID<Int>,
) : IntEntity(id) {
    companion object : IntEntityClass<ProductExtras>(ProductExtrasTable)

    /** Extra item name/description */
    var name by ProductExtrasTable.name

    var enName by ProductExtrasTable.enName

    var chName by ProductExtrasTable.chName

    /** Additional price for the extra item */
    var price by ProductExtrasTable.price

    /** Whether extra is active */
    var isActive by ProductExtrasTable.isActive

    /** Record update timestamp */
    var updatedAt by ProductExtrasTable.updatedAt

    /** Optional linked product (identity only; null = inline label) */
    var linkedProductId by ProductExtrasTable.linkedProductId
}
