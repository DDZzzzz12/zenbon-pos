/**
 * Exposed DAO entity for Bill.
 *
 * Provides object-relational mapping for bills with proper relationship
 * management and type-safe database operations. Central entity for billing
 * and payment processing.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.bill.domain.models

import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.database.tables.BillVatDetailTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.database.tables.OrderItemTable
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.database.tables.PaymentTable
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.features.zBon.domain.models.ZBon
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Bill entity for customer billing and payment processing.
 *
 * Manages customer bills including total amounts, tips, payment tracking,
 * and table information. Links bills to employees and supports tip distribution.
 */
class Bill(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<Bill>(BillTable)

    /** Reference to employee who created the bill */
    var employeeId by Employee referencedOn BillTable.employeeId

    /** Who receives the tip from this bill */
    var tipRecipient by BillTable.tipRecipient

    /** Table number where the bill was generated */
    var tableSession by TableSession referencedOn BillTable.tableSession

    /** Unique bill identifier for business operations */
    var billNumber by BillTable.billNumber

    /** Total bill amount with high precision */
    var billAmount by BillTable.billAmount

    /** Total discount amount applied to items in this bill */
    var discountAmount by BillTable.discountAmount

    /** Total promotional deduction amount (reduces taxable base) */
    var promotionAmount by BillTable.promotionAmount

    var paidAmount by BillTable.paidAmount

    /** Tip amount with high precision */
    var tips by BillTable.tips

    /** When the bill period started */
    var startTime by BillTable.startTime

    /** When the bill was completed */
    var endTime by BillTable.endTime

    var zBon by ZBon optionalReferencedOn BillTable.zBonId

    /** Reason for deletion if bill was deleted */
    var deletingReason by BillTable.deletingReason

    var deletedBill by Bill optionalReferencedOn BillTable.deletedBillId

    /** Set on a Storno Beleg when its reason matters to reports (Z-Bon, statistics). */
    var stornoReason by BillTable.stornoReason

    /** On a bill booked by a Zahlartwechsel: the stornoed original it replaces. */
    var rebooksBill by Bill optionalReferencedOn BillTable.rebooksBillId

    /** Reference to TSE transaction if signed */
    var tseTransaction by TseTransaction optionalReferencedOn BillTable.tseTransactionId

    /** VAT details for this bill */
    val vatDetails by BillVatDetail referrersOn BillVatDetailTable.billId

    /** Items on this bill */
    val items by OrderItem optionalReferrersOn OrderItemTable.billId

    /** Payments for this bill */
    val payments by Payment referrersOn PaymentTable.billId

    /** TSE signing status for queue-based signing (Nachsignieren) */
    var tseSigningStatus by BillTable.tseSigningStatus

    /** Training mode flag - bills in training mode are not fiscally relevant */
    var isTraining by BillTable.isTraining

    /** Reference to terminal that created this bill */
    var terminal by Terminal optionalReferencedOn BillTable.terminalId

    /**
     * See [BillTable.clearsSalesInvoiceId] — non-null means this bill is a **receivable clearing**,
     * a payment receipt rather than a sale.
     *
     * The id only; the invoice itself belongs to accounting and is reached by id.
     */
    var clearsSalesInvoiceId by BillTable.clearsSalesInvoiceId

    val refundPayments by Payment optionalReferrersOn PaymentTable.refundedBillId
}
