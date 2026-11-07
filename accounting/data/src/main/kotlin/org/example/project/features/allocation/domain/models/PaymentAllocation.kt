package org.example.project.features.allocation.domain.models

import org.example.project.features.allocation.database.tables.PaymentAllocationTable
import org.example.project.features.bank.domain.models.BankAccount
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.contract.domain.models.ContractInstalment
import org.example.project.features.document.domain.models.BankStatementTransaction
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

/**
 * Exposed DAO entity for one allocation — *this much of this payment went to that*.
 *
 * See [PaymentAllocationTable] for why both sides are typed rather than polymorphic.
 */
class PaymentAllocation(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<PaymentAllocation>(PaymentAllocationTable)

    /** [AllocationSourceKind] name. */
    var sourceKind by PaymentAllocationTable.sourceKind

    var bankTransaction by BankStatementTransaction optionalReferencedOn PaymentAllocationTable.bankTransactionId
    var kassenbuchHeader by KassenbuchHeader optionalReferencedOn PaymentAllocationTable.kassenbuchHeaderId

    /** See [PaymentAllocationTable.billId] — the clearing Bill a `TILL_RECEIPT` allocation came from. */
    var bill by Bill optionalReferencedOn PaymentAllocationTable.billId

    /** [AllocationTargetKind] name. */
    var targetKind by PaymentAllocationTable.targetKind

    var invoice by Invoice optionalReferencedOn PaymentAllocationTable.invoiceId

    /** An outgoing (case-C) invoice this payment settles — the AR mirror of [invoice]. */
    var salesInvoice by SalesInvoice optionalReferencedOn PaymentAllocationTable.salesInvoiceId

    /** The expected payment under a contract that this settles. */
    var contractInstalment by ContractInstalment optionalReferencedOn PaymentAllocationTable.contractInstalmentId

    var counterKassenbuchHeader by
        KassenbuchHeader optionalReferencedOn PaymentAllocationTable.counterKassenbuchHeaderId
    var counterBankAccount by BankAccount optionalReferencedOn PaymentAllocationTable.counterBankAccountId

    /** Always positive; direction comes from the source. */
    var amount by PaymentAllocationTable.amount

    var accountNo by PaymentAllocationTable.accountNo
    var bookingText by PaymentAllocationTable.bookingText

    /** [AllocationMatchedBy] name. */
    var matchedBy by PaymentAllocationTable.matchedBy

    /**
     * The `MatchSignal` name behind an accepted suggestion, null for a manual allocation — see
     * [PaymentAllocationTable.matchedSignal] for why it is not [ruleId].
     */
    var matchedSignal by PaymentAllocationTable.matchedSignal

    var ruleId by PaymentAllocationTable.ruleId
    var confidence by PaymentAllocationTable.confidence
    var note by PaymentAllocationTable.note
    var createdAt by PaymentAllocationTable.createdAt
    var createdBy by PaymentAllocationTable.createdBy

    override fun toString(): String = "PaymentAllocation(id=$id, source=$sourceKind, target=$targetKind, amount=$amount)"
}
