/**
 * Exposed DAO entity for ZBonPaymentSummary.
 *
 * Provides object-relational mapping for Z-report payment summaries with proper
 * relationship management and type-safe database operations. Supports detailed
 * payment reconciliation and reporting.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.zBon.domain.models

import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.zBon.database.tables.ZBonPaymentSummaryTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Z-report payment summary entity for payment reconciliation.
 *
 * Manages payment method summaries within Z-reports including totals
 * by payment type. Essential for cash reconciliation and payment
 * method performance analysis.
 */
class ZBonPaymentSummary(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<ZBonPaymentSummary>(ZBonPaymentSummaryTable)

    /** Reference to parent Z-report */
    var zBon by ZBon referencedOn ZBonPaymentSummaryTable.zbonId

    /** Payment method name (e.g., "Cash", "Credit Card") */
    var paymentType by PaymentType referencedOn ZBonPaymentSummaryTable.paymentTypeName

    var currency by ZBonPaymentSummaryTable.currency

    /** Total amount for this payment method */
    var amount by ZBonPaymentSummaryTable.amount
}
