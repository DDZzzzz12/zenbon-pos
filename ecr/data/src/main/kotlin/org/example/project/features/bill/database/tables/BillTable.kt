/**
 * Exposed table definition for bills.
 *
 * Defines the database schema structure for customer bills with proper
 * column types, constraints, and relationships. Central table for billing
 * and payment processing in the point-of-sale system.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.bill.database.tables

import org.example.project.core.domain.enums.bill.StornoReason
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.table.database.tables.TableSessionTable
import org.example.project.features.terminal.database.tables.TerminalTable
import org.example.project.features.tse.database.tables.TseTransactionTable
import org.example.project.features.zBon.database.tables.ZBonTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime
import java.math.BigDecimal

/**
 * Exposed table definition for bill table.
 *
 * Manages customer bills including total amounts, tips, payment tracking,
 * and table information. Links bills to employees and supports tip distribution.
 */
object BillTable : LongIdTable("bill", "id") {
    /** Reference to employee who created the birequest.tipsll */
    val employeeId = reference("employee_id", EmployeeTable)

    /** Who receives the tip from this bill */
    val tipRecipient = enumerationByName("tip_recipient", 50, TipRecipient::class).default(TipRecipient.EMPLOYEE)

    /** Table number where the bill was generated */
    val tableSession = reference("table_session", TableSessionTable)

    /** Unique bill identifier for business operations */
    val billNumber = varchar("bill_number", 100).uniqueIndex()

    /** Total bill amount with high precision */
    val billAmount = decimal("bill_amount", precision = 19, scale = 4)

    /** Total discount amount applied to items in this bill */
    val discountAmount = decimal("discount_amount", precision = 19, scale = 4).default(BigDecimal.ZERO)

    /** Total promotional deduction amount (reduces taxable base, unlike discount which is item-level) */
    val promotionAmount = decimal("promotion_amount", precision = 19, scale = 4).default(BigDecimal.ZERO)

    val paidAmount = decimal("paid_amount", precision = 19, scale = 4)

    /** Tip amount with high precision */
    val tips = decimal("tips", precision = 19, scale = 4).default(BigDecimal.ZERO)

    /** When the bill period started */
    val startTime = datetime("start_time")

    /** When the bill was completed */
    val endTime = datetime("end_time")

    /** Reason for deletion if bill was deleted */
    val deletingReason = varchar("deleting_reason", 10000).nullable()

    /** Reference to TSE transaction if signed */
    val tseTransactionId = reference("tse_transaction_id", TseTransactionTable).nullable()

    val deletedBillId = reference("deleted_bill_id", BillTable).nullable()

    /** Set on a Storno Beleg when its reason matters to reports (Z-Bon, statistics). */
    val stornoReason = enumerationByName("storno_reason", 32, StornoReason::class).nullable()

    /** On a bill booked by a Zahlartwechsel: the stornoed original it replaces. */
    val rebooksBillId = reference("rebooks_bill_id", BillTable).nullable()

    val zBonId = reference("zbon_id", ZBonTable).nullable()

    /** TSE signing status for tracking queue-based signing (Nachsignieren) */
    val tseSigningStatus =
        enumerationByName<TseSigningStatus>("tse_signing_status", 20)
            .default(TseSigningStatus.PENDING)

    /** Training mode flag - bills in training mode are not fiscally relevant */
    val isTraining = bool("is_training").default(false)

    /** Reference to terminal that created this bill */
    val terminalId = reference("terminal_id", TerminalTable).nullable()

    /**
     * Non-null marks this bill as a **receivable clearing** — a payment receipt for an invoice
     * settled at the counter, not a sale (slice 4c-2c).
     *
     * Such a bill has no order items and no `bill_vat_detail`: nothing was sold. Its [billAmount] is
     * the money taken, because `UMS_BRUTTO` derives from it and the standard requires the clearing to
     * report the payment amount — which is exactly why the Z-Bon must exclude it from revenue
     * explicitly rather than letting the derivation handle it.
     *
     * A plain id column: the invoice belongs to the accounting area, which sits above this one, so
     * the foreign key to `sales_invoice.id` (`fk_bill_clears_sales_invoice_id__id`, `ON DELETE
     * RESTRICT`) is declared by the host in `CrossAreaForeignKeys`, not here.
     */
    val clearsSalesInvoiceId = long("clears_sales_invoice_id").nullable()

    init {
        // Query hot paths — kept in sync with Flyway V86 (matching index names so fresh
        // installs (Exposed-created schema) and existing installs (migration) converge).
        index("idx_bill_end_time", false, endTime)
        index("idx_bill_employee_end_time", false, employeeId, endTime)
        index("idx_bill_table_session", false, tableSession)
        index("idx_bill_zbon_id", false, zBonId)
        index("idx_bill_deleted_bill_id", false, deletedBillId)
        // Kept in sync with Flyway V103.
        index("idx_bill_clears_sales_invoice_id", false, clearsSalesInvoiceId)
        // Kept in sync with Flyway V77.
        index("idx_bill_rebooks_bill_id", false, rebooksBillId)
    }
}
