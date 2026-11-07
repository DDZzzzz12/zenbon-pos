/**
 * Exposed DAO entity for VatGroup.
 *
 * Provides object-relational mapping for tax groups with proper relationship
 * management and type-safe database operations. Supports detailed tax
 * reporting and breakdown by tax rates.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.zBon.domain.models

import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.zBon.database.tables.VatGroupTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Tax group entity for detailed tax reporting and compliance.
 *
 * Manages tax rate groupings within Z-reports including net amounts,
 * tax amounts, and gross amounts per tax rate. Essential for tax
 * compliance and detailed financial reporting.
 */
class VatGroup(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<VatGroup>(VatGroupTable)

    /** Tax rate for this group */
    var vat by Vat referencedOn VatGroupTable.vatId

    /** Net amount (before tax) for this tax rate */
    var netAmount by VatGroupTable.netAmount

    /** Tax amount calculated for this rate */
    var vatAmount by VatGroupTable.vatAmount

    /** Gross amount (including tax) for this rate */
    var grossAmount by VatGroupTable.grossAmount

    /** Reference to parent Z-report */
    var zBon by ZBon referencedOn VatGroupTable.zbonId
}
