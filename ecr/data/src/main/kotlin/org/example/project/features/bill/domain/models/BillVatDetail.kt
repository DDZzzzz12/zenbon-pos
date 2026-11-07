/**
 * Exposed DAO entity for BillVatDetail.
 *
 * Provides object-relational mapping for bill VAT details with proper
 * relationship management and type-safe database operations. Supports
 * tax compliance and detailed financial reporting.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.bill.domain.models

import org.example.project.features.bill.database.tables.BillVatDetailTable
import org.example.project.features.payment.domain.models.Vat
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Bill VAT detail entity for tax compliance and reporting.
 *
 * Manages VAT breakdown for bills including net amounts, VAT amounts,
 * and total amounts per VAT rate. Essential for tax reporting and compliance.
 */
class BillVatDetail(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<BillVatDetail>(BillVatDetailTable)

    /** Reference to parent bill */
    var bill by Bill referencedOn BillVatDetailTable.billId

    /** Reference to applicable VAT rate */
    var vat by Vat referencedOn BillVatDetailTable.vatId

    /** VAT amount calculated for this rate */
    var vatAmount by BillVatDetailTable.vatAmount

    /** Net amount before VAT for this rate */
    var netAmount by BillVatDetailTable.netAmount

    /** Total amount including VAT for this rate */
    var totalAmount by BillVatDetailTable.totalAmount
}
