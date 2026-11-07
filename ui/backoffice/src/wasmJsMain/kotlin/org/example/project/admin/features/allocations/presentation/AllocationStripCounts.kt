package org.example.project.admin.features.allocations.presentation

import org.example.project.admin.core.presentation.belegfluss.InvoiceFlowCounts
import org.example.project.admin.core.presentation.belegfluss.OpenPaymentsSummary
import org.example.project.core.domain.dto.allocation.AllocationDto
import org.example.project.core.domain.dto.document.InvoiceListDto
import org.example.project.money.Decimal

/**
 * The Belegfluss strip as the Zuordnung screen shows it. Erfassen and Prüfen stay the invoice counts;
 * the *Zuordnen* tile is about **payments** here, so it is overridden with the queue:
 * - the count is the queue's server `total` while the *Nicht zugeordnet* filter is applied (every row is
 *   still open, so the total is exactly the open payments); under *Alle* it is not knowable and shows a dash;
 * - the open sum is the rows' open amounts, only when every row of the queue is loaded (`rows.size == total`)
 *   — a sum over a partial page would be a wrong number, so it is left out instead;
 * - *Erledigt* would need a second queue call (the *Alle* total), which the screen does not make, so it
 *   shows a dash rather than a wrong 0.
 */
object AllocationStripCounts {
    fun of(
        base: InvoiceFlowCounts,
        filter: AllocationDto.QueueFilter,
        total: Long,
        rows: List<AllocationDto.QueueRowDto>,
    ): InvoiceFlowCounts = base.withOpenPayments(summaryOf(filter, total, rows)).let { it.copy(unavailable = it.unavailable + InvoiceListDto.Bucket.FERTIG) }

    /**
     * The open payments of a queue answer: null under *Alle* (not every row is open, so the total is not
     * the open count), else the total with the rows' open sum when every row is loaded.
     */
    fun summaryOf(
        filter: AllocationDto.QueueFilter,
        total: Long,
        rows: List<AllocationDto.QueueRowDto>,
    ): OpenPaymentsSummary? {
        if (filter != AllocationDto.QueueFilter.NICHT_ZUGEORDNET) return null
        val sum = if (rows.size.toLong() == total) openSum(rows) else null
        return OpenPaymentsSummary(total = total, sum = sum, currency = rows.firstOrNull()?.currency)
    }

    /** The rows' open amounts added in [Decimal]; null if one does not parse. */
    fun openSum(rows: List<AllocationDto.QueueRowDto>): String? {
        var sum = Decimal.ZERO
        for (row in rows) sum += Decimal.parseOrNull(row.openAmount) ?: return null
        return sum.toPlainString()
    }
}
