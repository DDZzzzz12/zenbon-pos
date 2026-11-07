/**
 * Invoice Allowance/Charge Entity - Exposed DAO
 *
 * Domain model representing a document-level EN 16931 BG-20 allowance or BG-21 charge.
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.document.domain.models

import org.example.project.features.document.database.tables.InvoiceAllowanceChargeTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Exposed DAO entity for a document-level allowance or charge (EN 16931 BG-20/BG-21).
 */
class InvoiceAllowanceCharge(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<InvoiceAllowanceCharge>(InvoiceAllowanceChargeTable)

    /** Parent invoice. */
    var invoice by Invoice referencedOn InvoiceAllowanceChargeTable.invoiceId

    /** True for a charge (BG-21), false for an allowance/discount (BG-20). */
    var isCharge by InvoiceAllowanceChargeTable.isCharge
    var amount by InvoiceAllowanceChargeTable.amount
    var baseAmount by InvoiceAllowanceChargeTable.baseAmount
    var percentage by InvoiceAllowanceChargeTable.percentage
    var reasonCode by InvoiceAllowanceChargeTable.reasonCode
    var reasonText by InvoiceAllowanceChargeTable.reasonText
    var vatCategoryCode by InvoiceAllowanceChargeTable.vatCategoryCode
    var vatRate by InvoiceAllowanceChargeTable.vatRate

    override fun toString(): String = "InvoiceAllowanceCharge(id=$id, isCharge=$isCharge, amount=$amount)"
}
