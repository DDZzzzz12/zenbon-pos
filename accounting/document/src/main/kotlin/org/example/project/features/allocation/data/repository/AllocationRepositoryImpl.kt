package org.example.project.features.allocation.data.repository

import org.example.project.features.allocation.database.tables.PaymentAllocationTable
import org.example.project.features.allocation.domain.models.NewAllocation
import org.example.project.features.allocation.domain.models.PaymentAllocation
import org.example.project.features.allocation.domain.repository.AllocationRepository
import org.example.project.features.bank.domain.models.BankAccount
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.contract.domain.models.ContractInstalment
import org.example.project.features.document.domain.models.BankStatementTransaction
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.dao.entityCache
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.math.BigDecimal

/** Exposed implementation of [AllocationRepository]. Requires an ambient transaction. */
class AllocationRepositoryImpl : AllocationRepository {
    override fun create(allocation: NewAllocation): PaymentAllocation =
        PaymentAllocation.new {
            sourceKind = allocation.sourceKind.name
            bankTransaction = allocation.bankTransactionId?.let { existingBankTransaction(it) }
            kassenbuchHeader = allocation.kassenbuchHeaderId?.let { existingKassenbuchHeader(it) }
            bill = allocation.billId?.let { existingBill(it) }

            targetKind = allocation.targetKind.name
            invoice = allocation.invoiceId?.let { existingInvoice(it) }
            salesInvoice = allocation.salesInvoiceId?.let { existingSalesInvoice(it) }
            contractInstalment = allocation.contractInstalmentId?.let { existingContractInstalment(it) }
            counterKassenbuchHeader = allocation.counterKassenbuchHeaderId?.let { existingKassenbuchHeader(it) }
            counterBankAccount = allocation.counterBankAccountId?.let { existingBankAccount(it) }

            amount = allocation.amount
            accountNo = allocation.accountNo
            bookingText = allocation.bookingText
            matchedBy = allocation.matchedBy.name
            matchedSignal = allocation.matchedSignal
            ruleId = allocation.ruleId
            confidence = allocation.confidence
            note = allocation.note
            createdBy = allocation.createdBy
        }

    override fun findById(id: Long): PaymentAllocation? = PaymentAllocation.findById(id)

    override fun listForBankTransaction(transactionId: Long): List<PaymentAllocation> =
        PaymentAllocation.find { PaymentAllocationTable.bankTransactionId eq transactionId }.toList()

    override fun listForInvoice(invoiceId: Long): List<PaymentAllocation> = PaymentAllocation.find { PaymentAllocationTable.invoiceId eq invoiceId }.toList()

    /**
     * The entity cache is flushed first for the same reason [allocatedAmountsForBankTransaction]
     * does it: the DSL does not see pending DAO writes, and a sweep running after an allocation was
     * written in the same transaction would otherwise find nothing to void.
     */
    override fun allocationIdsForBankTransactions(transactionIds: List<Long>): List<Long> {
        if (transactionIds.isEmpty()) return emptyList()
        TransactionManager.current().entityCache.flush(listOf(PaymentAllocationTable))
        return PaymentAllocationTable
            .select(PaymentAllocationTable.id)
            .where { PaymentAllocationTable.bankTransactionId inList transactionIds }
            .map { it[PaymentAllocationTable.id].value }
    }

    override fun countByMatchedBy(): Map<String, Long> = countGroupedBy(PaymentAllocationTable.matchedBy).named()

    /** The null group — every manual assignment — is dropped: it is the absence of a signal, not one. */
    override fun countByMatchedSignal(): Map<String, Long> = countGroupedBy(PaymentAllocationTable.matchedSignal).named()

    private fun Map<String?, Long>.named(): Map<String, Long> = mapNotNull { (key, count) -> key?.to(count) }.toMap()

    /**
     * One `GROUP BY` per column, never a row-by-row tally: the count is over every allocation ever
     * made, and reading them all back to group them in Kotlin would grow without limit.
     *
     * The entity cache is flushed for the same reason [amountsFor] does it — the DSL does not see
     * DAO writes still pending in this transaction, and a caller asking for the counts right after
     * writing an allocation would otherwise be told the previous state.
     */
    private fun countGroupedBy(column: Column<out String?>): Map<String?, Long> {
        TransactionManager.current().entityCache.flush(listOf(PaymentAllocationTable))
        val tally = PaymentAllocationTable.id.count()
        return PaymentAllocationTable
            .select(column, tally)
            .groupBy(column)
            .associate { it[column] to it[tally] }
    }

    override fun delete(id: Long): Boolean {
        val existing = PaymentAllocation.findById(id) ?: return false
        existing.delete()
        return true
    }

    override fun allocatedAmountsForBankTransaction(transactionId: Long): List<BigDecimal> =
        amountsFor(PaymentAllocationTable.bankTransactionId eq transactionId)

    override fun allocatedAmountsForInvoice(invoiceId: Long): List<BigDecimal> = amountsFor(PaymentAllocationTable.invoiceId eq invoiceId)

    override fun allocatedAmountsForSalesInvoice(salesInvoiceId: Long): List<BigDecimal> = amountsFor(PaymentAllocationTable.salesInvoiceId eq salesInvoiceId)

    override fun allocatedAmountsForInstalment(instalmentId: Long): List<BigDecimal> = amountsFor(PaymentAllocationTable.contractInstalmentId eq instalmentId)

    /**
     * One query for the whole page. Both columns come out of the result row, so no
     * [PaymentAllocation] entity is materialised and no instalment reference is followed.
     */
    override fun allocatedAmountsForInstalments(instalmentIds: List<Long>): Map<Long, List<BigDecimal>> {
        if (instalmentIds.isEmpty()) return emptyMap()
        TransactionManager.current().entityCache.flush(listOf(PaymentAllocationTable))
        return PaymentAllocationTable
            .select(PaymentAllocationTable.contractInstalmentId, PaymentAllocationTable.amount)
            .where { PaymentAllocationTable.contractInstalmentId inList instalmentIds }
            .mapNotNull { row ->
                row[PaymentAllocationTable.contractInstalmentId]?.value?.to(row[PaymentAllocationTable.amount])
            }.groupBy({ it.first }, { it.second })
    }

    /**
     * Reads the amounts with the DSL, which does not see pending DAO writes on its own — so the
     * entity cache is flushed first. Without that, a residual recomputed straight after
     * [create] or [delete] in the same transaction would be computed from the *previous* state.
     */
    private fun amountsFor(condition: Op<Boolean>): List<BigDecimal> {
        TransactionManager.current().entityCache.flush(listOf(PaymentAllocationTable))
        return PaymentAllocationTable
            .select(PaymentAllocationTable.amount)
            .where(condition)
            .map { it[PaymentAllocationTable.amount] }
    }

    private fun existingBankTransaction(id: Long) = requireNotNull(BankStatementTransaction.findById(id)) { "No bank transaction with id=$id" }

    private fun existingKassenbuchHeader(id: Long) = requireNotNull(KassenbuchHeader.findById(id)) { "No cash-book entry with id=$id" }

    private fun existingBill(id: Long) = requireNotNull(Bill.findById(id)) { "No bill with id=$id" }

    private fun existingInvoice(id: Long) = requireNotNull(Invoice.findById(id)) { "No invoice with id=$id" }

    private fun existingSalesInvoice(id: Long) = requireNotNull(SalesInvoice.findById(id)) { "No sales invoice with id=$id" }

    private fun existingBankAccount(id: Long) = requireNotNull(BankAccount.findById(id)) { "No bank account with id=$id" }

    private fun existingContractInstalment(id: Long) = requireNotNull(ContractInstalment.findById(id)) { "No contract instalment with id=$id" }
}
