package org.example.project.features.document.data.repository

import org.example.project.features.document.database.tables.InvoiceBookingTable
import org.example.project.features.document.database.tables.InvoiceTable
import org.jetbrains.exposed.v1.core.*
import java.math.BigDecimal

/**
 * What an invoice still owes, defined **once** for every query that asks.
 *
 * `open_amount` is not written on insert — neither `ParsedInvoiceWriter` nor `:accounting-banking`'s parser
 * sets it, and the V94 backfill only touched rows that existed when it ran — so it is NULL on every
 * invoice that arrives through the e-invoice pipeline, which is the normal path. A query that reads
 * that column alone therefore reports a supplier we owe thousands as owing nothing, while looking
 * perfectly healthy: the rows are there, the sum is just zero.
 *
 * That is not hypothetical. The Rechnungen queue coalesced and the Lieferanten aggregates did not,
 * so the two screens disagreed about the same seven invoices — the queue showed Metro AG at
 * €4.819,50 open while the creditor list beside it showed €0,00, and ordering the creditors by open
 * total ordered them all as NULL. Both now call in here, so a third caller cannot reintroduce the
 * split by writing the predicate out again.
 *
 * Public rather than `internal` because the custom-export catalogue lives in `:pos-server` and its
 * "Nur offene" toggle asks the very same question. Module visibility would have forced a fourth
 * spelling of the predicate into a second module — the exact split this object exists to prevent —
 * so the boundary gives way rather than the definition.
 */
object InvoiceResidual {
    /** `open_amount`, else `amount_due`, else the gross. Mirrors [stillOpen] term for term. */
    fun expression(): ExpressionWithColumnType<BigDecimal?> = Coalesce(InvoiceTable.openAmount, InvoiceTable.amountDue, InvoiceTable.taxInclusiveTotal)

    /**
     * The rows [expression] is worth summing over.
     *
     * Written as an explicit NULL cascade for readability, not for correctness: `Coalesce(...) > 0`
     * is **equivalent**, because SQL `COALESCE` returns the first *non-NULL* argument and a zero is
     * non-NULL, so it stops at a settled `open_amount = 0` exactly as this cascade does. An earlier
     * version of this comment claimed coalescing would "resurrect" such an invoice as fully unpaid;
     * that was wrong, and was corrected after `SalesInvoiceResidualTest` exercised both forms across
     * every NULL pattern and found them identical. The cascade stays because it states the rule
     * where a reader looks for it, term by term against [expression].
     *
     * What the object genuinely guards against is different and still real: reading `open_amount`
     * **alone**, on a column that is NULL until the allocation writer first touches it.
     */
    fun stillOpen(): Op<Boolean> =
        (InvoiceTable.openAmount greater BigDecimal.ZERO) or
            (
                InvoiceTable.openAmount.isNull() and
                    (
                        (InvoiceTable.amountDue greater BigDecimal.ZERO) or
                            (
                                InvoiceTable.amountDue.isNull() and
                                    (InvoiceTable.taxInclusiveTotal greater BigDecimal.ZERO)
                            )
                    )
            )

    /**
     * Not yet paid by us.
     *
     * Requires the caller to have joined [InvoiceBookingTable] — an invoice with no booking row at
     * all is unpaid, which a left join expresses as a NULL `paid_at`.
     */
    fun unpaid(): Op<Boolean> = InvoiceBookingTable.paidAt.isNull()
}
