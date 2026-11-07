package org.example.project.features.payment.domain.models

import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.payment.database.tables.PaymentTable
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.voucher.database.tables.VoucherRedeemTable
import org.example.project.features.voucher.domain.models.VoucherRedeem
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Exposed DAO entity for Payment.
 *
 * Provides object-relational mapping for payments with proper relationship
 * management and type-safe database operations. Supports payment processing
 * and tracking.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * Payment entity for payment processing and tracking.
 *
 * Manages individual payments for bills including payment method, amount,
 * status tracking, and external transaction references for comprehensive
 * payment processing and reconciliation.
 */
class Payment(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<Payment>(PaymentTable)

    /** Reference to bill being paid */
    var bill by Bill.Companion referencedOn PaymentTable.billId

    /** Reference to payment method used */
    var paymentType by PaymentType.Companion referencedOn PaymentTable.paymentTypeId

    /** Payment amount with high precision */
    var amount by PaymentTable.amount

    var currency by PaymentTable.currency

    /** See [PaymentTable.createsReceivable] — the frozen snapshot, never the type's live flag. */
    var createsReceivable by PaymentTable.createsReceivable

    /** Current status of the payment */
    var paymentStatus by PaymentTable.paymentStatus

    /** When the payment was processed */
    var paymentTime by PaymentTable.paymentTime

    var refundedBillId by Bill optionalReferencedOn PaymentTable.refundedBillId
    var refundTime by PaymentTable.refundTime

    /** External payment gateway transaction reference */
    var transactionId by PaymentTable.transactionId

    /** Voucher redemption linked to this payment, if any */
    val voucherRedeem by VoucherRedeem optionalBackReferencedOn VoucherRedeemTable.paymentId
}
