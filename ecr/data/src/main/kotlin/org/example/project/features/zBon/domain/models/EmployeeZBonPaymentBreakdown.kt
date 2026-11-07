/**
 * Exposed DAO entity for EmployeeZBonPaymentBreakdown.
 *
 * Provides object-relational mapping for per-employee payment type breakdowns
 * within Z-reports. Each record represents the total amount an employee received
 * via a specific payment method during the Z-report period.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.zBon.domain.models

import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.zBon.database.tables.EmployeeZBonPaymentBreakdownTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Employee Z-Bon payment breakdown entity.
 *
 * Tracks how much revenue an employee received via each payment method
 * within a Z-report period. Essential for detailed employee revenue analysis.
 */
class EmployeeZBonPaymentBreakdown(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<EmployeeZBonPaymentBreakdown>(EmployeeZBonPaymentBreakdownTable)

    /** Reference to parent employee Z-Bon summary record */
    var employeeZBonSummary by EmployeeZBonSummary referencedOn EmployeeZBonPaymentBreakdownTable.employeeZBonSummaryId

    /** Payment method */
    var paymentType by PaymentType referencedOn EmployeeZBonPaymentBreakdownTable.paymentTypeId

    /** Currency code (e.g., "EUR") */
    var currency by EmployeeZBonPaymentBreakdownTable.currency

    /** Total amount for this payment type */
    var amount by EmployeeZBonPaymentBreakdownTable.amount
}
