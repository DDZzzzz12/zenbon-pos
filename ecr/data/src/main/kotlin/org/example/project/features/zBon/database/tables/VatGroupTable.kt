/**
 * Exposed table definition for tax groups in reports.
 *
 * Defines the database schema structure for tax groupings within reports with proper
 * column types and constraints. Supports detailed tax reporting and breakdown
 * by tax rates for compliance and financial analysis.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.zBon.database.tables

import org.example.project.features.payment.database.tables.VatTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Exposed table definition for tax_group table.
 *
 * Manages tax rate groupings within Z-reports including net amounts,
 * tax amounts, and gross amounts per tax rate. Essential for tax
 * compliance and detailed financial reporting.
 */
object VatGroupTable : LongIdTable("tax_group", "id") {
    /** Tax rate for this group */
    val vatId = reference("vat_id", VatTable)

    /** Net amount (before tax) for this tax rate */
    val netAmount = decimal("net_amount", precision = 19, scale = 4)

    /** Tax amount calculated for this rate */
    val vatAmount = decimal("tax_amount", precision = 19, scale = 4)

    /** Gross amount (including tax) for this rate */
    val grossAmount = decimal("gross_amount", precision = 19, scale = 4)

    /** Reference to parent Z-report */
    val zbonId = reference("zbon_id", ZBonTable)
}
