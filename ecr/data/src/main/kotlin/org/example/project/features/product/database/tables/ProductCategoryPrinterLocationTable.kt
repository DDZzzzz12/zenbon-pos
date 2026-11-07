/**
 * Exposed junction table for product category and printer location many-to-many relationship.
 *
 * Defines the association between product categories and their designated printer locations.
 * This enables category-specific printing where different product categories can be
 * printed to different printers (e.g., kitchen vs bar printers).
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.product.database.tables

import org.example.project.features.printer.database.tables.PrinterLocationTable
import org.jetbrains.exposed.v1.core.Table

/**
 * Junction table for many-to-many relationship between ProductCategory and PrinterLocation.
 *
 * Links product categories with their designated printer locations, allowing for
 * category-specific printing workflows (e.g., food items to kitchen printer,
 * drinks to bar printer).
 */
object ProductCategoryPrinterLocationTable : Table("product_category_printer_location") {
    /** Reference to product category */
    val productCategoryId = reference("product_category_id", ProductCategoryTable)

    /** Reference to printer location */
    val printerLocationId = reference("printer_location_id", PrinterLocationTable)

    /** Composite primary key */
    override val primaryKey = PrimaryKey(productCategoryId, printerLocationId)
}
