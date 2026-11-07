package org.example.project.features.payment.database.tables

import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.features.bill.database.tables.BillTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Exposed table definition for payments.
 *
 * Defines the database schema structure for payments with proper column types,
 * constraints, and relationships. Supports payment processing, tracking, and
 * integration with external payment systems.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * Exposed table definition for payment table.
 *
 * Manages individual payments for bills including payment method, amount,
 * status tracking, and external transaction references for comprehensive
 * payment processing and reconciliation.
 */
object PaymentTable : LongIdTable("payment", "id") {
    /** Reference to bill being paid (note: Java entity has unique constraint) */
    val billId = reference("bill_id", BillTable)

    /** Reference to payment method used */
    val paymentTypeId = reference("payment_type_id", PaymentTypeTable)

    /** Payment amount with high precision */
    val amount = decimal("amount", precision = 19, scale = 4)

    /** Current status of the payment */
    val paymentStatus = enumerationByName("payment_status", 50, PaymentStatus::class)

    /** When the payment was processed */
    val paymentTime = datetime("payment_time")

    val refundedBillId = reference("refunded_bill_id", BillTable).nullable()

    val currency = varchar("currency", 10)

    /**
     * Frozen at settlement from `payment_type.creates_receivable` — **never read live**.
     *
     * The DSFinV-K export, the Z-Bon and the AR origin all read this snapshot, so a shop that marks
     * a tender receivable tomorrow does not rewrite what last month's bills reported.
     */
    val createsReceivable = bool("creates_receivable").default(false)

    val refundTime = datetime("refund_time").nullable()

    /** External payment gateway transaction reference */
    val transactionId = varchar("transaction_id", 255).nullable()

    init {
        // Query hot paths — kept in sync with Flyway V86 (matching index names so fresh
        // installs (Exposed-created schema) and existing installs (migration) converge).
        index("idx_payment_bill_id", false, billId)
        index("idx_payment_payment_type_id", false, paymentTypeId)
        index("idx_payment_refunded_bill_id", false, refundedBillId)
    }
}
