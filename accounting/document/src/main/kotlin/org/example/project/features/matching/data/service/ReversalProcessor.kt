package org.example.project.features.matching.data.service

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import org.example.project.features.allocation.data.service.AllocationWriter
import org.example.project.features.allocation.database.tables.PaymentAllocationTable
import org.example.project.features.allocation.domain.models.PaymentAllocation
import org.example.project.features.allocation.domain.repository.AllocationRepository
import org.example.project.features.document.database.tables.BankStatementTable
import org.example.project.features.document.database.tables.BankStatementTransactionTable
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Un-allocates what a returned direct debit undid.
 *
 * Spec §7 in one line: *a transaction with `reversal_indicator` voids the allocations of the entry
 * it reverses, returning the invoice to open.* Without it the worst failure in this feature happens
 * quietly — the money came back, the supplier is still owed, and the open-item list says everything
 * is settled. Nobody goes looking for a problem the system says does not exist.
 *
 * Which entry a reversal reverses is [findReversedEntry]'s decision, and **no pairing means no
 * voiding**: the reversal is left in the assignment queue for a human. Voiding the wrong entry would
 * un-pay an invoice that *was* paid, which is the same invisible wrong state in the opposite
 * direction.
 *
 * ## Every swept reversal is stamped, including one that paired with nothing
 *
 * The sweep has looked and found nothing, which is a conclusion rather than a failure — re-deciding
 * it every night would never reach a different answer. The marker also carries the case it exists
 * for: an operator may decide the *return* was itself the error and re-allocate the original by
 * hand, and without [BankStatementTransactionTable.reversalProcessedAt] the next sweep would undo
 * their work again, silently, forever.
 *
 * A reversal on a statement with no bank account is skipped **without** being stamped, so it is
 * swept once an operator resolves the statement. That is 5a's eligibility rule reused; [isEligible]
 * itself cannot be called here, because it rejects every reversal by definition — that a reversal
 * settles no invoice is exactly why this class exists.
 *
 * **Requires an ambient transaction** and never opens its own: the voids, the residuals they restore
 * and the markers that stop them repeating must commit together. Four queries whatever the row
 * counts — the reversals, the candidate window, the allocations, and one batched stamp.
 */
class ReversalProcessor(
    private val writer: AllocationWriter,
    private val repository: AllocationRepository,
) {
    /**
     * Sweeps every reversal not yet concluded on, and returns **how many allocations were voided** —
     * not how many reversals were looked at, because that is the number an operator would want
     * explained. [sweepAndReport] returns the allocations themselves.
     */
    fun sweep(now: LocalDateTime = currentTimeStamp()): Int = sweepAndReport(now).size

    /**
     * Sweeps every reversal not yet concluded on, and returns **the allocations it voided**, each
     * with the reversal that caused it and the target that is open again.
     *
     * Returned rather than logged here: this class runs inside the caller's transaction and the
     * audit writer opens its own, so recording is the caller's step, taken once the voids have
     * committed. Without it a Rücklastschrift re-opened an invoice with nothing on record saying so.
     *
     * Voiding goes through [AllocationWriter.remove] rather than a delete, so both residuals are
     * recomputed by the one path `ResidualInvariantTest` proves honest. A direct delete would fall
     * outside it and leave an invoice showing as settled with nothing allocated to it.
     */
    fun sweepAndReport(now: LocalDateTime = currentTimeStamp()): List<VoidedAllocation> {
        val reversals = unsweptReversals().filter { it.facts.hasBankAccount }
        if (reversals.isEmpty()) return emptyList()

        val candidates = candidatesAround(reversals.map { it.facts.valueDate })
        // reversed entry id -> the reversal that undid it
        val reversalOf =
            reversals
                .mapNotNull { reversal ->
                    findReversedEntry(reversal.facts, candidates[reversal.bankAccountId].orEmpty())
                        ?.let { reversed -> reversed.id to reversal.facts.id }
                }.toMap()

        val voided = voidAllocationsOf(reversalOf)
        stamp(reversals.map { it.facts.id }, now)
        return voided
    }

    private fun voidAllocationsOf(reversalOf: Map<Long, Long>): List<VoidedAllocation> =
        repository.allocationIdsForBankTransactions(reversalOf.keys.toList()).mapNotNull { allocationId ->
            val record = repository.findById(allocationId)?.toVoided(reversalOf) ?: return@mapNotNull null
            writer.remove(allocationId)
            record
        }

    /**
     * What the audit record needs, read off the row before it is deleted. Ids come from `readValues`
     * so no referenced entity is loaded just to learn its key.
     */
    private fun PaymentAllocation.toVoided(reversalOf: Map<Long, Long>): VoidedAllocation? {
        val reversedId = readValues[PaymentAllocationTable.bankTransactionId]?.value ?: return null
        val targetId =
            readValues[PaymentAllocationTable.invoiceId]?.value
                ?: readValues[PaymentAllocationTable.salesInvoiceId]?.value
                ?: readValues[PaymentAllocationTable.contractInstalmentId]?.value
        return VoidedAllocation(
            allocationId = id.value,
            reversedTransactionId = reversedId,
            reversalTransactionId = reversalOf.getValue(reversedId),
            targetKind = targetKind,
            targetId = targetId,
            amount = amount,
        )
    }

    /**
     * Marks the reversals as concluded on in one statement rather than one per row. Written with the
     * DSL, not the entity: nothing else in this transaction reads the column back, and the stamp is
     * a bookkeeping fact about the sweep rather than part of the money it moved.
     */
    private fun stamp(
        reversalIds: List<Long>,
        now: LocalDateTime,
    ) {
        BankStatementTransactionTable.update({ BankStatementTransactionTable.id inList reversalIds }) {
            it[reversalProcessedAt] = now
        }
    }

    /** The reversals no sweep has drawn a conclusion about yet, joined to the statement for its account. */
    private fun unsweptReversals(): List<SweepCandidate> =
        BankStatementTransactionTable
            .innerJoin(BankStatementTable)
            .selectAll()
            .where {
                (BankStatementTransactionTable.reversalIndicator eq true) and
                    BankStatementTransactionTable.reversalProcessedAt.isNull()
            }.map { row ->
                SweepCandidate(
                    facts = row.toReversalFacts(),
                    bankAccountId = row[BankStatementTable.bankAccountId]?.value,
                )
            }

    /**
     * Everything that could be the entry a reversal reverses, grouped by **which of our accounts it
     * is on**.
     *
     * The grouping is a guard, not an optimisation: a return arrives on the account the debit left,
     * so an entry on a different account of ours is not what it undoes — and under signal 3, which
     * has no identifier behind it, two of our accounts paying the same supplier the same amount is
     * an ordinary thing that must not become a coin flip.
     *
     * Reversals are excluded here as well as in [findReversedEntry] so the window read stays small;
     * the rule keeps its own guard because it is pure and must hold whatever it is handed.
     */
    private fun candidatesAround(reversalDates: List<LocalDate>): Map<Long?, List<TransactionFacts>> {
        val earliest = reversalDates.min().minus(REVERSAL_WINDOW_DAYS, DateTimeUnit.DAY).atTime(0, 0)
        val latest = reversalDates.max().atTime(END_OF_DAY_HOUR, END_OF_DAY_MINUTE)
        return BankStatementTransactionTable
            .innerJoin(BankStatementTable)
            .selectAll()
            .where {
                (BankStatementTransactionTable.reversalIndicator eq false) and
                    (BankStatementTransactionTable.valueDate greaterEq earliest) and
                    (BankStatementTransactionTable.valueDate lessEq latest)
            }.groupBy({ it[BankStatementTable.bankAccountId]?.value }, { it.toReversalFacts() })
    }

    /**
     * Only the fields [findReversedEntry] reads. `hasBankAccount` comes off the joined statement
     * rather than through the `bankAccount` accessor, which would load the whole account row.
     */
    private fun ResultRow.toReversalFacts(): TransactionFacts =
        TransactionFacts(
            id = this[BankStatementTransactionTable.id].value,
            valueDate = this[BankStatementTransactionTable.valueDate].date,
            amount = this[BankStatementTransactionTable.amount],
            creditDebit = this[BankStatementTransactionTable.creditDebit],
            counterpartyIban = this[BankStatementTransactionTable.counterpartyIban],
            endToEndId = this[BankStatementTransactionTable.endToEndId],
            accountServicerReference = this[BankStatementTransactionTable.accountServicerReference],
            reversalIndicator = this[BankStatementTransactionTable.reversalIndicator],
            hasBankAccount = this[BankStatementTable.bankAccountId] != null,
        )

    /** A reversal and the account it arrived on — the two things the sweep pairs it by. */
    private data class SweepCandidate(
        val facts: TransactionFacts,
        val bankAccountId: Long?,
    )

    private companion object {
        const val END_OF_DAY_HOUR = 23
        const val END_OF_DAY_MINUTE = 59
    }
}
