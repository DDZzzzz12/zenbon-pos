package org.example.project.features.payment.database.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Exposed table definition for payment types.
 *
 * Defines the database schema structure for payment method types with proper
 * column types and constraints. Supports various payment methods configuration
 * and management in the point-of-sale system.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * Exposed table definition for payment_type table.
 *
 * Manages different payment methods available in the system such as
 * cash, credit card, digital payments, etc. Allows for payment method
 * configuration and activation/deactivation.
 */
object PaymentTypeTable : LongIdTable("payment_type", "id") {
    /** Payment method name (e.g., "Cash", "Credit Card", "PayPal") */
    val name = varchar("name", 100).uniqueIndex()

    val enName = varchar("en_name", 100).nullable()

    val chName = varchar("ch_name", 100).nullable()

    val isCash = bool("is_cash").default(false)

    val isVoucher = bool("is_voucher").default(false)

    /**
     * Whether settling with this tender creates a **receivable** — the customer owes the money
     * rather than having handed it over (typically `ÜBERWEISUNG`).
     *
     * Master data, so it is editable; each `payment` row freezes its own copy at settlement
     * ([org.example.project.features.payment.database.tables.PaymentTable.createsReceivable]),
     * because reclassifying an already-settled bill is the "money received twice" defect.
     */
    val createsReceivable = bool("creates_receivable").default(false)

    /** Whether this payment method is currently active/available */
    val isActive = bool("is_active")

    /** Record update timestamp */
    val updatedAt = datetime("updated_at")
}
