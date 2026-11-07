package org.example.project.features.allocation.data.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.features.allocation.database.tables.PaymentAllocationTable
import org.example.project.features.allocation.domain.models.PaymentAllocation
import org.example.project.features.allocation.domain.repository.AllocationQueueRepository
import org.example.project.features.contract.database.tables.ContractInstalmentTable
import org.example.project.features.contract.domain.models.ContractInstalment
import org.example.project.features.contract.domain.models.ContractInstalmentStatus
import org.example.project.features.document.data.repository.InvoiceResidual
import org.example.project.features.document.database.tables.BankStatementTable
import org.example.project.features.document.database.tables.BankStatementTransactionTable
import org.example.project.features.document.database.tables.InvoiceBookingTable
import org.example.project.features.document.database.tables.InvoiceTable
import org.example.project.features.document.domain.models.BankStatementTransaction
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.salesinvoice.data.repository.SalesInvoiceResidual
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceTable
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal

/**
 * See [AllocationQueueRepository].
 *
 * ## Why the open predicates coalesce
 *
 * Nothing writes `open_amount` on insert — `:accounting-banking`'s parser and `ParsedInvoiceWriter` both create
 * rows without it, and V94 only back-filled what existed when it ran. A plain `open_amount > 0`
 * would therefore stop listing everything imported after the deploy while looking perfectly healthy.
 * The expressions below spell out the same fallback `AllocationWriter` computes, and
 * `AllocationQueueRepositoryTest`'s first and fifth tests are written against NULL-residual rows so
 * a drift between the two fails.
 * `overpaidInvoiceCondition` is the deliberate exception — coalescing there would report a negative
 * *stated* total (a Gutschrift filed as a Rechnung) as an overpayment nobody made; see its KDoc.
 *
 * **Duplicates are filtered out** in [queueCondition] via `duplicate_of_transaction_id IS NULL`,
 * per spec §6. The revealable count that spec also asks for is not built yet — duplicates are
 * currently invisible here rather than collapsed behind a toggle.
 */
class AllocationQueueRepositoryImpl : AllocationQueueRepository {
    override fun queue(
        unassignedOnly: Boolean,
        bookedFrom: LocalDateTime?,
        limit: Int,
        offset: Long,
    ): List<BankStatementTransaction> {
        val query =
            BankStatementTransactionTable
                .innerJoin(BankStatementTable)
                .selectAll()
                .where { queueCondition(unassignedOnly, bookedFrom) }
                .orderBy(BankStatementTransactionTable.valueDate to SortOrder.DESC)
                .limit(limit)
                .offset(offset)
        return BankStatementTransaction.wrapRows(query).toList()
    }

    override fun countQueue(
        unassignedOnly: Boolean,
        bookedFrom: LocalDateTime?,
    ): Long =
        BankStatementTransactionTable
            .innerJoin(BankStatementTable)
            .selectAll()
            .where { queueCondition(unassignedOnly, bookedFrom) }
            .count()

    override fun openInvoices(
        supplierId: Long?,
        overdueAsOf: LocalDateTime?,
        bookedFrom: LocalDateTime?,
        limit: Int,
        offset: Long,
    ): List<Invoice> {
        val query =
            InvoiceTable
                .leftJoin(InvoiceBookingTable)
                .selectAll()
                .where { openInvoiceCondition(supplierId, overdueAsOf, bookedFrom) }
                // NULLS LAST with an id tiebreaker: an invoice with no stated due date is not the
                // most urgent thing to show, and without the tiebreaker equal (or all-NULL) due
                // dates leave the order to the engine — which differs between H2 and Postgres, so
                // paging could repeat or skip a row in production while the tests stayed green.
                .orderBy(InvoiceTable.dueDate to SortOrder.ASC_NULLS_LAST, InvoiceTable.id to SortOrder.ASC)
                .limit(limit)
                .offset(offset)
        return Invoice.wrapRows(query).toList()
    }

    override fun countOpenInvoices(
        supplierId: Long?,
        overdueAsOf: LocalDateTime?,
        bookedFrom: LocalDateTime?,
    ): Long =
        InvoiceTable
            .leftJoin(InvoiceBookingTable)
            .selectAll()
            .where { openInvoiceCondition(supplierId, overdueAsOf, bookedFrom) }
            .count()

    override fun overdueInvoiceIdsAmong(
        invoiceIds: Collection<Long>,
        overdueAsOf: LocalDateTime,
        bookedFrom: LocalDateTime?,
    ): Set<Long> {
        if (invoiceIds.isEmpty()) return emptySet()
        return InvoiceTable
            .leftJoin(InvoiceBookingTable)
            .select(InvoiceTable.id)
            .where { (InvoiceTable.id inList invoiceIds) and openInvoiceCondition(null, overdueAsOf, bookedFrom) }
            .map { it[InvoiceTable.id].value }
            .toSet()
    }

    override fun openSalesInvoices(
        debtorId: Long?,
        overdueAsOf: LocalDateTime?,
        bookedFrom: LocalDateTime?,
        limit: Int,
        offset: Long,
    ): List<SalesInvoice> {
        val query =
            SalesInvoiceTable
                .selectAll()
                .where { openSalesInvoiceCondition(debtorId, overdueAsOf, bookedFrom) }
                .orderBy(SalesInvoiceTable.issueDate to SortOrder.ASC, SalesInvoiceTable.id to SortOrder.ASC)
                .limit(limit)
                .offset(offset)
        return SalesInvoice.wrapRows(query).toList()
    }

    override fun countOpenSalesInvoices(
        debtorId: Long?,
        overdueAsOf: LocalDateTime?,
        bookedFrom: LocalDateTime?,
    ): Long =
        SalesInvoiceTable
            .selectAll()
            .where { openSalesInvoiceCondition(debtorId, overdueAsOf, bookedFrom) }
            .count()

    override fun overpaidInvoices(
        bookedFrom: LocalDateTime?,
        limit: Int,
        offset: Long,
    ): List<Invoice> {
        val query =
            InvoiceTable
                .selectAll()
                .where { overpaidInvoiceCondition(bookedFrom) }
                .orderBy(InvoiceTable.openAmount to SortOrder.ASC, InvoiceTable.id to SortOrder.ASC)
                .limit(limit)
                .offset(offset)
        return Invoice.wrapRows(query).toList()
    }

    override fun countOverpaidInvoices(bookedFrom: LocalDateTime?): Long =
        InvoiceTable
            .selectAll()
            .where { overpaidInvoiceCondition(bookedFrom) }
            .count()

    override fun sumOpenInvoices(
        supplierId: Long?,
        overdueAsOf: LocalDateTime?,
        bookedFrom: LocalDateTime?,
    ): BigDecimal {
        val residual = invoiceResidualExpression().sum()
        return InvoiceTable
            .leftJoin(InvoiceBookingTable)
            .select(residual)
            .where { openInvoiceCondition(supplierId, overdueAsOf, bookedFrom) }
            .firstOrNull()
            ?.get(residual) ?: BigDecimal.ZERO
    }

    override fun sumOverpaidInvoices(bookedFrom: LocalDateTime?): BigDecimal {
        // Only `open_amount` here, and no coalescing: the overpaid bucket is defined by that column
        // being below zero, so a fallback would be reading a value the bucket does not select on.
        val residual = InvoiceTable.openAmount.sum()
        val total =
            InvoiceTable
                .select(residual)
                .where { overpaidInvoiceCondition(bookedFrom) }
                .firstOrNull()
                ?.get(residual) ?: BigDecimal.ZERO
        // Reported as a magnitude: the stored residual is negative, and „Überzahlt −20,00 €" reads
        // as a credit rather than as an overpayment.
        return total.negate()
    }

    /**
     * The residual an open invoice still owes.
     *
     * Delegates to [InvoiceResidual] so the sum cannot drift from the predicate that decided which
     * rows it covers — nor from the creditor list, which sums the same thing per supplier.
     */
    private fun invoiceResidualExpression(): ExpressionWithColumnType<BigDecimal?> = InvoiceResidual.expression()

    override fun openInstalments(
        bookedFrom: LocalDateTime?,
        limit: Int,
        offset: Long,
    ): List<ContractInstalment> {
        val query =
            ContractInstalmentTable
                .selectAll()
                .where { openInstalmentCondition(bookedFrom) }
                .orderBy(ContractInstalmentTable.dueDate to SortOrder.ASC, ContractInstalmentTable.id to SortOrder.ASC)
                .limit(limit)
                .offset(offset)
        return ContractInstalment.wrapRows(query).toList()
    }

    override fun countOpenInstalments(bookedFrom: LocalDateTime?): Long =
        ContractInstalmentTable
            .selectAll()
            .where { openInstalmentCondition(bookedFrom) }
            .count()

    /**
     * The key comes from `readValues` rather than `bankTransaction?.id`, which would load the whole
     * referenced entity per row and turn the one query this method promises into N+1.
     */
    override fun allocationsForTransactions(transactionIds: List<Long>): Map<Long, List<PaymentAllocation>> {
        if (transactionIds.isEmpty()) return emptyMap()
        return PaymentAllocation
            .find { PaymentAllocationTable.bankTransactionId inList transactionIds }
            .toList()
            .mapNotNull { allocation ->
                val transactionId = allocation.readValues[PaymentAllocationTable.bankTransactionId]?.value
                transactionId?.let { it to allocation }
            }.groupBy({ it.first }, { it.second })
    }

    /**
     * Resolved statements only; with [unassignedOnly], those whose residual is still above zero.
     *
     * `open_amount IS NULL` is treated as "the full amount is still open" rather than as zero — see
     * the class KDoc. `amount` is positive by convention (direction lives in `credit_debit`), so the
     * NULL branch also excludes the degenerate zero-amount entry, which could otherwise sit in the
     * queue forever: allocating zero is rejected.
     *
     * [bookedFrom] applies to **both** filters, not just the unassigned one: a row hidden from
     * *Nicht zugeordnet* but still present in *Alle* would be a confusing half-rule. `value_date` is
     * NOT NULL, so the queue has no undated-row case to reason about.
     */
    private fun queueCondition(
        unassignedOnly: Boolean,
        bookedFrom: LocalDateTime?,
    ): Op<Boolean> {
        // A duplicate is the same money re-delivered by an overlapping statement, so allocating it
        // would pay an invoice twice. V91's own header states a matcher MUST filter on this column;
        // until it did, duplicates were both queued and suggested. Unconditional rather than
        // gated on [unassignedOnly]: a duplicate is not queue work in *any* filter, and a row that
        // vanished from "Nicht zugeordnet" only to reappear under "Alle" would read as a bug.
        var condition: Op<Boolean> =
            BankStatementTable.bankAccountId.isNotNull() and
                BankStatementTransactionTable.duplicateOfTransactionId.isNull()
        if (unassignedOnly) {
            val stillOpen =
                (BankStatementTransactionTable.openAmount greater BigDecimal.ZERO) or
                    (
                        BankStatementTransactionTable.openAmount.isNull() and
                            (BankStatementTransactionTable.amount greater BigDecimal.ZERO)
                    )
            condition = condition and stillOpen
        }
        if (bookedFrom != null) {
            condition = condition and (BankStatementTransactionTable.valueDate greaterEq bookedFrom)
        }
        return condition
    }

    /**
     * `open_amount`, else `amount_due`, else `tax_inclusive_total` — above zero, and not paid.
     *
     * With [overdueAsOf], narrowed further to invoices due before the start of that instant's day ([OverdueCutoff]); with [bookedFrom],
     * to invoices issued on or after the Buchungsbeginn.
     */
    private fun openInvoiceCondition(
        supplierId: Long?,
        overdueAsOf: LocalDateTime?,
        bookedFrom: LocalDateTime?,
    ): Op<Boolean> {
        var condition = InvoiceResidual.stillOpen() and InvoiceResidual.unpaid()
        if (supplierId != null) condition = condition and (InvoiceTable.supplierId eq supplierId)
        // `dueDate less x` is already false for NULL in SQL, but the explicit isNotNull states the
        // rule where a reader looks for it: an unknown due date is not a late one.
        if (overdueAsOf != null) {
            condition = condition and InvoiceTable.dueDate.isNotNull() and (InvoiceTable.dueDate less OverdueCutoff.of(overdueAsOf))
        }
        // An invoice with no issue date stays in: we cannot show it is out of scope, and dropping
        // it silently is the worse error. `value_date` is NOT NULL, so the queue has no such case.
        if (bookedFrom != null) {
            condition =
                condition and
                (InvoiceTable.issueDate.isNull() or (InvoiceTable.issueDate greaterEq bookedFrom))
        }
        return condition
    }

    /**
     * The AR mirror of [openInvoiceCondition], with the lifecycle as the gate: `sales_invoice` has
     * no `paid_at`, so [SalesInvoiceResidual.issued] is what makes a row a live obligation.
     * `CANCELLED` rows never qualify; a `POS_SETTLED` invoice's `open_amount` is 0 from birth, so it
     * drops out of [SalesInvoiceResidual.stillOpen] on its own.
     *
     * Both halves come from the shared definition, which `SalesInvoiceRepositoryImpl.list` also
     * calls — so the Rechnungsübersicht and this queue cannot disagree about the same invoice.
     */
    private fun openSalesInvoiceCondition(
        debtorId: Long?,
        overdueAsOf: LocalDateTime?,
        bookedFrom: LocalDateTime?,
    ): Op<Boolean> {
        var condition: Op<Boolean> = SalesInvoiceResidual.stillOpen() and SalesInvoiceResidual.issued()
        if (debtorId != null) {
            condition = condition and (SalesInvoiceTable.debtorId eq debtorId)
        }
        if (overdueAsOf != null) {
            condition = condition and SalesInvoiceTable.dueDate.isNotNull() and (SalesInvoiceTable.dueDate less OverdueCutoff.of(overdueAsOf))
        }
        if (bookedFrom != null) {
            condition = condition and (SalesInvoiceTable.issueDate greaterEq bookedFrom)
        }
        return condition
    }

    /**
     * `open_amount`, else `expected_amount` — above zero, and not written off.
     *
     * `status` is a stored `varchar` compared against the enum's name, the way
     * `InstalmentStatusDerivation` already reads it. With [bookedFrom], narrowed to instalments due
     * on or after the Buchungsbeginn; `due_date` is NOT NULL, so there is no undated case.
     */
    private fun openInstalmentCondition(bookedFrom: LocalDateTime?): Op<Boolean> {
        val stillOpen =
            (ContractInstalmentTable.openAmount greater BigDecimal.ZERO) or
                (
                    ContractInstalmentTable.openAmount.isNull() and
                        (ContractInstalmentTable.expectedAmount greater BigDecimal.ZERO)
                )
        var condition = stillOpen and (ContractInstalmentTable.status neq ContractInstalmentStatus.WAIVED.name)
        if (bookedFrom != null) {
            condition = condition and (ContractInstalmentTable.dueDate greaterEq bookedFrom)
        }
        return condition
    }

    /**
     * `open_amount < 0` — and deliberately nothing else. See [AllocationQueueRepository.overpaidInvoices]
     * for why this one must not coalesce and must not consult `paid_at`.
     */
    private fun overpaidInvoiceCondition(bookedFrom: LocalDateTime?): Op<Boolean> {
        var condition: Op<Boolean> = InvoiceTable.openAmount less BigDecimal.ZERO
        // Same rule as the open bucket: an invoice with no issue date stays in, because we cannot
        // show it is out of scope and dropping it silently is the worse error.
        if (bookedFrom != null) {
            condition =
                condition and
                (InvoiceTable.issueDate.isNull() or (InvoiceTable.issueDate greaterEq bookedFrom))
        }
        return condition
    }
}
