/**
 * Exposed table definition for employee Z-Bon payment breakdowns.
 *
 * Defines the database schema for tracking how much revenue each employee
 * received via each payment method within a Z-report period. Links each
 * breakdown entry to an employee Z-Bon summary record and a payment type.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.zBon.database.tables

import org.example.project.features.payment.database.tables.PaymentTypeTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Exposed table definition for employee_zbon_payment_breakdown table.
 *
 * Stores per-employee payment type breakdown within a Z-report,
 * showing how much revenue each employee received via each payment method.
 */
object EmployeeZBonPaymentBreakdownTable : LongIdTable("employee_zbon_payment_breakdown", "id") {
    /** Reference to parent employee Z-Bon summary record */
    val employeeZBonSummaryId = reference("employee_zbon_summary_id", EmployeeZBonSummaryTable)

    /** Reference to payment type */
    val paymentTypeId = reference("payment_type_id", PaymentTypeTable)

    /** Currency code (e.g., "EUR") */
    val currency = varchar("currency", 3)

    /** Total amount for this payment type */
    val amount = decimal("amount", precision = 19, scale = 4)
}
