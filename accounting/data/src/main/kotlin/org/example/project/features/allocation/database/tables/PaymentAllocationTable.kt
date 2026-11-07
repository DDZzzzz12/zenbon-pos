package org.example.project.features.allocation.database.tables

import org.example.project.features.bank.database.tables.BankAccountTable
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.contract.database.tables.ContractInstalmentTable
import org.example.project.features.document.database.tables.BankStatementTransactionTable
import org.example.project.features.document.database.tables.InvoiceTable
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceTable
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * One row means *this much of this payment went to that*.
 *
 * Both sides are **typed**: a small kind enum plus one nullable FK per case, never a polymorphic id.
 * That keeps referential integrity real — a deleted invoice cannot leave an allocation pointing at
 * nothing — at the cost of several mostly-null columns, which is the right trade for money.
 *
 * Money moves **from** a bank transaction or a cash-book entry, **to** an invoice, a contract
 * instalment, the other leg of a cash transit, another of our own accounts, or a bare account when
 * there is no document at all.
 *
 * **Nothing here writes to `kassenbuch_header`.** Those rows are TSE-signed and lockable, so an
 * assignment has to be an additive row beside them rather than a mutation of them.
 */
object PaymentAllocationTable : LongIdTable("payment_allocation", "id") {
    /** `AllocationSourceKind` name. */
    val sourceKind = varchar("source_kind", 20)

    val bankTransactionId =
        optReference("bank_transaction_id", BankStatementTransactionTable, onDelete = ReferenceOption.CASCADE)
    val kassenbuchHeaderId =
        optReference("kassenbuch_header_id", KassenbuchHeaderTable, onDelete = ReferenceOption.CASCADE)

    /**
     * Set when [sourceKind] is `TILL_RECEIPT`: the clearing Bill the money arrived on (slice 4c-2c).
     *
     * A till receipt is neither a bank transaction nor a cash-book entry — a card clearing has no
     * place in a cash book — so it carries its own reference, like the other two sources do.
     */
    val billId = optReference("bill_id", BillTable, onDelete = ReferenceOption.RESTRICT)

    /** `AllocationTargetKind` name. */
    val targetKind = varchar("target_kind", 30)

    val invoiceId = optReference("invoice_id", InvoiceTable, onDelete = ReferenceOption.CASCADE)

    /**
     * An outgoing (case-C) invoice this payment settles — the AR mirror of [invoiceId].
     * RESTRICT, not CASCADE: a draft never has allocations and an issued invoice is never deleted.
     */
    val salesInvoiceId =
        optReference("sales_invoice_id", SalesInvoiceTable, onDelete = ReferenceOption.RESTRICT)

    /** The expected payment under a contract that this settles. */
    val contractInstalmentId =
        optReference("contract_instalment_id", ContractInstalmentTable, onDelete = ReferenceOption.SET_NULL)

    /** The matching Kassenbuch entry for a `CASH_TRANSIT` — the other half of a Geldtransit. */
    val counterKassenbuchHeaderId =
        optReference("counter_kassenbuch_header_id", KassenbuchHeaderTable, onDelete = ReferenceOption.SET_NULL)

    /** The other own account for an `INTERNAL_TRANSFER`. */
    val counterBankAccountId =
        optReference("counter_bank_account_id", BankAccountTable, onDelete = ReferenceOption.SET_NULL)

    /** Always **positive**; direction comes from the source, matching ISO 20022 `CdtDbtInd`. */
    val amount = decimal("amount", 19, 4)

    /** For targets with no document behind them — `EXPENSE_NO_DOC`, `FEE`. */
    val accountNo = varchar("account_no", 20).nullable()
    val bookingText = varchar("booking_text", 60).nullable()

    /** `AllocationMatchedBy` name — so a wrong rule can be found and undone. */
    val matchedBy = varchar("matched_by", 20)

    /**
     * The `MatchSignal` name that produced an accepted suggestion; **null** for a manual allocation,
     * which claims no signal.
     *
     * [matchedBy] alone answers "are suggestions used at all". It cannot answer the question worth
     * asking — *which of the seven signals earn their place* — because the signal is not stored
     * anywhere. This column is what lets the ladder be judged on evidence rather than opinion.
     *
     * Deliberately **not** [ruleId]: that and [confidence] stay reserved for a future auto-rule
     * engine, and writing a signal name into a column meaning "which rule row" would be the same
     * category error `supplier.creditor_no` invited — a number that looks like an answer to a
     * different question.
     */
    val matchedSignal = varchar("matched_signal", 40).nullable()

    val ruleId = long("rule_id").nullable()
    val confidence = decimal("confidence", 5, 4).nullable()
    val note = text("note").nullable()

    /**
     * MUST stay `clientDefault { }`, not `default(value)` — the latter freezes every row's timestamp
     * to server start, and an entity `init` block cannot write it because Exposed assigns
     * `Entity.klass` only after the constructor has run.
     */
    val createdAt = datetime("created_at").clientDefault { currentTimeStamp() }
    val createdBy = varchar("created_by", 64).nullable()

    init {
        index("idx_payment_allocation_bank_transaction_id", false, bankTransactionId)
        index("idx_payment_allocation_kassenbuch_header_id", false, kassenbuchHeaderId)
        index("idx_payment_allocation_invoice_id", false, invoiceId)
        index("idx_payment_allocation_contract_instalment_id", false, contractInstalmentId)
        index("idx_payment_allocation_sales_invoice_id", false, salesInvoiceId)
        index("idx_payment_allocation_bill_id", false, billId)
    }
}
