/**
 * Exposed DAO entity for PrinterLocation.
 *
 * Provides object-relational mapping for printer location with proper
 * relationship management and type-safe database operations. Links
 * physical or logical printer locations with printer configurations.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.printer.domain.models

import org.example.project.features.printer.database.tables.PrinterLocationTable
import org.example.project.features.product.database.tables.ProductCategoryPrinterLocationTable
import org.example.project.features.product.domain.models.ProductCategory
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Printer location entity for distributed printing management.
 *
 * Manages printer location configuration linking specific locations
 * with printer setups and defining printing capabilities per location.
 * Supports distributed printing in multi-location environments.
 */
class PrinterLocation(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<PrinterLocation>(PrinterLocationTable)

    /** Location identifier/name for the printer */
    var printerLocation by PrinterLocationTable.printerLocation

    /** Whether this location can print bills/receipts */
    var printBill by PrinterLocationTable.printBill

    /** Whether this printer location is active (soft delete) */
    var isActive by PrinterLocationTable.isActive

    /** Language for print formatting (determines character encoding and localization) */
    var language by PrinterLocationTable.language

    /** Beep alert mode for order ticket notifications */
    var beepMode by PrinterLocationTable.beepMode

    /** Reference to printer setup configuration */
    var printerSetup by PrinterSetup referencedOn PrinterLocationTable.printerSetupId

    /** Associated product categories through many-to-many relationship */
    var productCategories by ProductCategory via ProductCategoryPrinterLocationTable
}
