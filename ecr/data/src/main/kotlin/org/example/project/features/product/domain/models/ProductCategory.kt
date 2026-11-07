/**
 * Exposed DAO entity for ProductCategory.
 *
 * Provides object-relational mapping for product categories with proper
 * relationship management and type-safe database operations. Supports
 * product organization and menu management functionality.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.product.domain.models

import org.example.project.features.printer.domain.models.PrinterLocation
import org.example.project.features.product.database.tables.ProductCategoryPrinterLocationTable
import org.example.project.features.product.database.tables.ProductCategoryTable
import org.example.project.features.product.database.tables.ProductTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Product category entity for menu organization and inventory management.
 *
 * Manages product categories with sorting capabilities and relationships
 * to extras and printer locations for comprehensive product management.
 */
class ProductCategory(
    id: EntityID<Int>,
) : IntEntity(id) {
    companion object : IntEntityClass<ProductCategory>(ProductCategoryTable)

    /** Category name */
    var name by ProductCategoryTable.name

    var enName by ProductCategoryTable.enName

    var chName by ProductCategoryTable.chName

    /** Category description */
    var description by ProductCategoryTable.description

    /** Whether category is active */
    var isActive by ProductCategoryTable.isActive

    /** Record update timestamp */
    var updatedAt by ProductCategoryTable.updatedAt

    var sortPosition by ProductCategoryTable.sortPosition

    /** Products in this category */
    val products by Product referrersOn ProductTable.categoryId

    var visibility by ProductCategoryTable.visibility

    /** Associated printer locations through many-to-many relationship */
    var printerLocations by PrinterLocation via ProductCategoryPrinterLocationTable
}
