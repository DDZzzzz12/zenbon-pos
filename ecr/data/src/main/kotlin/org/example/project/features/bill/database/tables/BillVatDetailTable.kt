/**
 * Exposed table definition for bill VAT details.
 *
 * Defines the database schema structure for VAT breakdown on bills with proper
 * column types and constraints. Supports tax compliance and detailed financial
 * reporting by tracking VAT amounts at the bill level.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.bill.database.tables

import org.example.project.features.payment.database.tables.VatTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Exposed table definition for bill_vat_detail table.
 *
 * Manages VAT breakdown for bills including net amounts, VAT amounts,
 * and total amounts per VAT rate. Essential for tax reporting and compliance.
 */
object BillVatDetailTable : LongIdTable("bill_vat_detail", "id") {
    /** Reference to parent bill */
    val billId = reference("bill_id", BillTable)

    /** Reference to applicable VAT rate */
    val vatId = reference("vat_id", VatTable)

    /** VAT amount calculated for this rate */
    val vatAmount = decimal("vat_amount", precision = 19, scale = 4)

    /** Net amount before VAT for this rate */
    val netAmount = decimal("net_amount", precision = 19, scale = 4)

    /** Total amount including VAT for this rate */
    val totalAmount = decimal("total_amount", precision = 19, scale = 4)
}
