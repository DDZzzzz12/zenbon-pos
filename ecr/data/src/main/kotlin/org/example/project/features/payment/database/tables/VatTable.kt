package org.example.project.features.payment.database.tables

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable

/**
 * Exposed table definition for VAT (Value Added Tax) rates.
 *
 * Defines the database schema structure for VAT configuration with proper
 * column types and constraints. Supports tax calculation and compliance
 * requirements for different product categories.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * Exposed table definition for vat table.
 *
 * Manages VAT rates and classes for proper tax calculation on orders and bills.
 * Essential for tax compliance and accurate financial reporting.
 */
object VatTable : IdTable<String>("vat") {
    override val id: Column<EntityID<String>> = varchar("id", 10).entityId()
    override val primaryKey = PrimaryKey(id)

    /** VAT classification identifier */
    val vatClass = varchar("vat_class", 50).uniqueIndex()

    /** VAT rate as decimal (e.g., 0.19 for 19%) */
    val vatRate = decimal("vat_rate", precision = 5, scale = 4)
}
