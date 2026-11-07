package org.example.project.features.payment.domain.models

import org.example.project.features.payment.database.tables.VatTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.EntityClass

/**
 * Exposed DAO entity for VAT (Value Added Tax) rates.
 *
 * Provides object-relational mapping for VAT configuration with proper
 * relationship management and type-safe database operations. Supports
 * tax calculation and compliance requirements.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * VAT entity for tax calculation and compliance.
 *
 * Manages VAT rates and classes for proper tax calculation on orders and bills.
 * Essential for tax compliance and accurate financial reporting.
 */
class Vat(
    id: EntityID<String>,
) : Entity<String>(id) {
    companion object : EntityClass<String, Vat>(VatTable)

    /** VAT classification identifier */
    var vatClass by VatTable.vatClass

    /** VAT rate as decimal (e.g., 0.19 for 19%) */
    var vatRate by VatTable.vatRate
}
