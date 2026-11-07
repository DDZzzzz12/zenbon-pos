package org.example.project.features.allocation.domain.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.features.allocation.domain.models.PaymentAllocation
import org.example.project.features.contract.domain.models.ContractInstalment
import org.example.project.features.document.domain.models.BankStatementTransaction
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import java.math.BigDecimal

/**
 * The read side of Zuordnung: what still needs assigning, and what it can be assigned to.
 *
 * **Every method requires an ambient transaction**, like every other repository here — the returned
 * Exposed entities are lazy and throw once the transaction has closed, so the caller must map inside
 * the same `dbQuery`.
 *
 * Separate from `AllocationRepository` because that one is the write path's data access, guarded by
 * `AllocationWriter`. These are plain queries with no invariant to keep.
 *
 * `bookedFrom` is the Buchungsbeginn: rows dated before it are excluded from open items and
 * from the queue, though they stay visible everywhere else. Null means no cut-off is set, and
 * then **nothing is filtered** — an unset setting must not empty a work list.
 */
interface AllocationQueueRepository {
    /**
     * Transactions on statements **resolved to one of our accounts**, newest value date first.
     *
     * An unresolved statement's entries are excluded entirely: with no account there is no shop, so
     * the cross-shop guard could not be evaluated even in principle. That is a Bank-tab work item,
     * not a Zuordnung one.
     *
     * With [unassignedOnly], only rows whose effective residual is above zero — `open_amount` when
     * set, otherwise the full `amount`, because a NULL means no residual has been **computed** yet,
     * not that the entry is assigned.
     */
    fun queue(
        unassignedOnly: Boolean,
        bookedFrom: LocalDateTime?,
        limit: Int,
        offset: Long,
    ): List<BankStatementTransaction>

    fun countQueue(
        unassignedOnly: Boolean,
        bookedFrom: LocalDateTime?,
    ): Long

    /**
     * Invoices that still owe something: effective residual above zero — `open_amount`, else
     * `amount_due`, else `tax_inclusive_total` — and no `paid_at` on the booking.
     *
     * An invoice with none of the three is **excluded**: nothing tells us anything is owed, and
     * listing an unknown as a debt is worse than omitting it.
     *
     * [overdueAsOf] narrows to invoices **due before the start of that instant's day** — an invoice
     * due today is not yet overdue. An invoice with no due date is
     * never overdue — we do not know when it was due, and unknown must not render as late. It is a
     * parameter rather than `now()` inside the query so a test can control the clock.
     *
     * The third clause of the open-invoice invariant is `issue_date >= bookedFrom`. An invoice with
     * **no** issue date stays in: we cannot show it is out of scope, and dropping it silently is the
     * worse error.
     *
     * Ordered by due date ascending with **undated invoices last** and `id` as the tiebreaker, so
     * paging is deterministic: a due date is frequently absent, and an unordered page boundary can
     * repeat or skip a row.
     */
    fun openInvoices(
        supplierId: Long?,
        overdueAsOf: LocalDateTime?,
        bookedFrom: LocalDateTime?,
        limit: Int,
        offset: Long,
    ): List<Invoice>

    fun countOpenInvoices(
        supplierId: Long?,
        overdueAsOf: LocalDateTime?,
        bookedFrom: LocalDateTime?,
    ): Long

    /**
     * The ids among [invoiceIds] that are overdue as of [overdueAsOf] — exactly the rows
     * [openInvoices] returns for `overdueAsOf`, so a row flag cannot disagree with the Überfällig
     * bucket. One query for a page.
     */
    fun overdueInvoiceIdsAmong(
        invoiceIds: Collection<Long>,
        overdueAsOf: LocalDateTime,
        bookedFrom: LocalDateTime?,
    ): Set<Long>

    /**
     * Case-C (STANDALONE) invoices that still expect money, earliest issue first.
     *
     * The AR mirror of [openInvoices], with the lifecycle as the gate: `sales_invoice` has no
     * `paid_at` — `status = ISSUED` is what makes it a live obligation. Effective residual above
     * zero — `open_amount`, else `amount_due`, else `tax_inclusive_total` — and `CANCELLED` rows
     * never qualify. [bookedFrom] filters `issue_date >= Buchungsbeginn`.
     */
    fun openSalesInvoices(
        debtorId: Long?,
        overdueAsOf: LocalDateTime?,
        bookedFrom: LocalDateTime?,
        limit: Int,
        offset: Long,
    ): List<SalesInvoice>

    fun countOpenSalesInvoices(
        debtorId: Long?,
        overdueAsOf: LocalDateTime?,
        bookedFrom: LocalDateTime?,
    ): Long

    /**
     * Invoices that have been paid **more** than they asked for: `open_amount` strictly below zero.
     *
     * This predicate deliberately does **not** coalesce to `amount_due`/`tax_inclusive_total` the way
     * [openInvoices] does. Only a residual that was actually **computed** can be an overpayment: a
     * coalescing predicate would instead report any invoice whose *stated total* is negative — a
     * Gutschrift captured as an ordinary Rechnung — as overpaid, although nothing was ever paid
     * against it. What is asked here is what the allocations did, not what the document claims.
     *
     * `paid_at` is **not** consulted. An overpayment is an arithmetic fact about the money, not a
     * workflow state, and marking an invoice paid must not be able to hide it — that silent
     * disappearance is the defect this query exists to fix.
     *
     * Ordered by residual ascending (most overpaid first) with `id` as the tiebreaker, so paging is
     * deterministic across engines.
     */
    fun overpaidInvoices(
        bookedFrom: LocalDateTime?,
        limit: Int,
        offset: Long,
    ): List<Invoice>

    fun countOverpaidInvoices(bookedFrom: LocalDateTime?): Long

    /**
     * What the open bucket is *worth*: the residual summed over the same rows [countOpenInvoices]
     * counts, with the same arguments meaning the same things.
     *
     * The residual is the coalesced `open_amount`, else `amount_due`, else `tax_inclusive_total` —
     * the fallback the bucket filters on. Summing `open_amount` alone would report every freshly
     * parsed invoice as worth nothing while still listing it as open, because nothing writes that
     * column on insert.
     *
     * Zero for an empty bucket, never null.
     */
    fun sumOpenInvoices(
        supplierId: Long?,
        overdueAsOf: LocalDateTime?,
        bookedFrom: LocalDateTime?,
    ): BigDecimal

    /**
     * How much was overpaid, as a **positive magnitude** — not the negative residual it is stored
     * as. A tile reading „Überzahlt −20,00 €" reads as a credit, which is the opposite of what an
     * over-allocated invoice means.
     *
     * Zero for an empty bucket, never null.
     */
    fun sumOverpaidInvoices(bookedFrom: LocalDateTime?): BigDecimal

    /**
     * Contract instalments that still expect money: effective residual above zero — `open_amount`,
     * else `expected_amount` — and not `WAIVED`.
     *
     * The residual coalesces for the same reason [openInvoices] does: nothing writes `open_amount`
     * on insert, so a NULL there means the whole expectation is still open rather than that it is
     * settled. An instalment with **no** expected amount is excluded — a variable obligation whose
     * amount nobody has stated yet is not a figure we can offer as a target.
     *
     * [bookedFrom] filters on `due_date`, which is `NOT NULL` — so unlike the invoice list there is
     * no undated row to make an exception for.
     *
     * Ordered by due date ascending with `id` as the tiebreaker, so paging is deterministic.
     */
    fun openInstalments(
        bookedFrom: LocalDateTime?,
        limit: Int,
        offset: Long,
    ): List<ContractInstalment>

    fun countOpenInstalments(bookedFrom: LocalDateTime?): Long

    /** One query for a whole page of queue rows, rather than one per row. */
    fun allocationsForTransactions(transactionIds: List<Long>): Map<Long, List<PaymentAllocation>>
}
