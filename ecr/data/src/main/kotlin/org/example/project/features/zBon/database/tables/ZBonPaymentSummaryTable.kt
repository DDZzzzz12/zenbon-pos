/**
 * Exposed table definition for Z-report payment summaries.
 *
 * Defines the database schema structure for payment method summaries within
 * Z-reports with proper column types and constraints. Supports detailed
 * payment reconciliation and reporting by payment type.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.zBon.database.tables

import org.example.project.features.payment.database.tables.PaymentTypeTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Exposed table definition for zbon_payment_summary table.
 *
 * Manages payment method summaries within Z-reports including totals
 * by payment type. Essential for cash reconciliation and payment
 * method performance analysis.
 */
object ZBonPaymentSummaryTable : LongIdTable("zbon_payment_summary", "id") {
    /** Reference to parent Z-report */
    val zbonId = reference("zbon_id", ZBonTable)

    /** Payment method name (e.g., "Cash", "Credit Card") */
    val paymentTypeName = reference("payment_type_name", PaymentTypeTable)

    val currency = varchar("currency", 3)

    /** Total amount for this payment method */
    val amount = decimal("amount", precision = 19, scale = 4)
}
