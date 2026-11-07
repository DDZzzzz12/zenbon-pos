package org.example.project.features.allocation.domain.repository

import org.example.project.features.allocation.domain.models.NewAllocation
import org.example.project.features.allocation.domain.models.PaymentAllocation
import java.math.BigDecimal

/**
 * Plain data access over `payment_allocation`.
 *
 * **Every method requires an ambient transaction** — the repository never opens its own `dbQuery`,
 * because an allocation and the two residuals it changes have to be one atomic write. Pairing the
 * write with the residuals is `AllocationWriter`'s job, not this interface's; nothing else should
 * call [create] or [delete] directly.
 *
 * The three `allocatedAmountsFor…` methods return the individual amounts rather than a SQL `SUM`, so
 * the residual is computed by the one pure formula every reading shares.
 */
interface AllocationRepository {
    fun create(allocation: NewAllocation): PaymentAllocation

    fun findById(id: Long): PaymentAllocation?

    fun listForBankTransaction(transactionId: Long): List<PaymentAllocation>

    fun listForInvoice(invoiceId: Long): List<PaymentAllocation>

    /**
     * The ids of every allocation made from any of [transactionIds], in one query.
     *
     * Ids rather than entities, because the one caller — the reversal sweep — hands each straight to
     * `AllocationWriter.remove`, which re-reads the row it is about to void along with the two
     * residuals it has to restore. Asking per transaction would be an N+1 growing with the number of
     * returned debits in a night.
     */
    fun allocationIdsForBankTransactions(transactionIds: List<Long>): List<Long>

    /**
     * How many allocations carry each `matched_by`, as one grouped query rather than a scan.
     *
     * Only the values actually present come back — filling in the constants nothing has used is the
     * service's job, because *which* constants exist is a domain question and not this table's.
     */
    fun countByMatchedBy(): Map<String, Long>

    /** The same, for `matched_signal`; rows with none — every manual assignment — are excluded. */
    fun countByMatchedSignal(): Map<String, Long>

    /** False when nothing had that id. */
    fun delete(id: Long): Boolean

    fun allocatedAmountsForBankTransaction(transactionId: Long): List<BigDecimal>

    fun allocatedAmountsForInvoice(invoiceId: Long): List<BigDecimal>

    /** The AR mirror of [allocatedAmountsForInvoice], for a `SALES_INVOICE` target. */
    fun allocatedAmountsForSalesInvoice(salesInvoiceId: Long): List<BigDecimal>

    fun allocatedAmountsForInstalment(instalmentId: Long): List<BigDecimal>

    /**
     * The same amounts as [allocatedAmountsForInstalment], for many instalments in one query and
     * grouped by instalment id.
     *
     * An absent key means nothing has been allocated to that instalment — which reads as *no
     * evidence of what it cost*, not as an amount of zero. Asking per instalment would be an N+1
     * growing with the number of periods on the page.
     */
    fun allocatedAmountsForInstalments(instalmentIds: List<Long>): Map<Long, List<BigDecimal>>
}
