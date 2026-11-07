package org.example.project.features.payment.domain.models

import org.example.project.features.payment.database.tables.PaymentTable
import org.example.project.features.payment.database.tables.PaymentTypeTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Exposed DAO entity for PaymentType.
 *
 * Provides object-relational mapping for payment types with proper
 * relationship management and type-safe database operations. Supports
 * payment method configuration and management.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * Payment type entity for payment method management.
 *
 * Manages different payment methods available in the system such as
 * cash, credit card, digital payments, etc. Allows for payment method
 * configuration and activation/deactivation.
 */
class PaymentType(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<PaymentType>(PaymentTypeTable)

    /** Payment method name (e.g., "Cash", "Credit Card", "PayPal") */
    var name by PaymentTypeTable.name

    var enName by PaymentTypeTable.enName

    var chName by PaymentTypeTable.chName

    var isCash by PaymentTypeTable.isCash

    /** See [PaymentTypeTable.createsReceivable]. Master data; each payment freezes its own copy. */
    var createsReceivable by PaymentTypeTable.createsReceivable

    var isVoucher by PaymentTypeTable.isVoucher

    /** Whether this payment method is currently active/available */
    var isActive by PaymentTypeTable.isActive

    /** Record update timestamp */
    var updatedAt by PaymentTypeTable.updatedAt

    /** Payments using this payment type */
    val payments by Payment.Companion referrersOn PaymentTable.paymentTypeId
}
