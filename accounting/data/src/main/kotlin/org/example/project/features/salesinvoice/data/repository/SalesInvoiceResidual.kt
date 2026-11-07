package org.example.project.features.salesinvoice.data.repository

import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceTable
import org.jetbrains.exposed.v1.core.Coalesce
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import java.math.BigDecimal

/**
 * What an outgoing invoice still owes **us**, defined once for every query that asks.
 *
 * The AR mirror of [InvoiceResidual][org.example.project.features.document.data.repository.InvoiceResidual],
 * and it exists for the same reason. `open_amount` is a residual *maintained by the allocation
 * writer*, not a value written on insert: it is NULL on every invoice nothing has ever been allocated
 * against, which is every invoice until the first payment arrives. A query that reads that column
 * alone therefore reports a customer who owes thousands as owing nothing, while looking perfectly
 * healthy — the rows are there, the sum is merely zero. `amount_due` (BT-115) and then the gross
 * (`tax_inclusive_total`) are the fallbacks.
 *
 * On the creditor side that defect was not hypothetical: the Rechnungen queue coalesced and the
 * Lieferanten aggregates did not, so two screens disagreed about the same seven invoices — €4.819,50
 * open on one, €0,00 on the other. Here the cascade had been written out twice by hand instead, once
 * in `SalesInvoiceRepositoryImpl` and once in `AllocationQueueRepositoryImpl`, differing only in
 * parenthesisation and so far agreeing by luck. Both now call in here, so a third caller cannot
 * reintroduce the split by spelling the predicate again.
 *
 * Public rather than `internal` because the custom-export catalogue lives in `:pos-server` and its
 * "Nur offene" toggle asks the very same question. Module visibility would have forced a third
 * spelling of the predicate into a second module — the exact split this object exists to prevent —
 * so the boundary gives way rather than the definition.
 */
object SalesInvoiceResidual {
    /** `open_amount`, else `amount_due`, else the gross. Mirrors [stillOpen] term for term. */
    fun expression(): ExpressionWithColumnType<BigDecimal?> =
        Coalesce(SalesInvoiceTable.openAmount, SalesInvoiceTable.amountDue, SalesInvoiceTable.taxInclusiveTotal)

    /**
     * The rows [expression] is worth summing over.
     *
     * The rule the cascade encodes: the fallback moves on only when a column is **unstated**, never
     * when it states a zero. An invoice settled to `open_amount = 0` is closed — a `POS_SETTLED` one
     * is born that way — and falling through that zero to the gross would resurrect it as fully
     * unpaid. What must never replace this is a bare `open_amount > 0`: the column is NULL until the
     * allocation writer first touches the invoice, and reading it alone is the defect that left the
     * AP screens disagreeing.
     *
     * Written out rather than as `Coalesce(...) > 0` because it states that rule where a reader
     * looks for it, and mirrors the AP twin term for term. The two forms are in fact **equivalent** —
     * SQL `COALESCE` falls through NULLs only, so it stops at a stated zero exactly as this does;
     * `SalesInvoiceResidualTest` was run against both and is green either way. `InvoiceResidual`'s
     * KDoc claims otherwise, and is wrong on that one point. Keep the explicit form for legibility,
     * not because a coalesce would be unsafe.
     */
    fun stillOpen(): Op<Boolean> =
        (SalesInvoiceTable.openAmount greater BigDecimal.ZERO) or
            (
                SalesInvoiceTable.openAmount.isNull() and
                    (
                        (SalesInvoiceTable.amountDue greater BigDecimal.ZERO) or
                            (
                                SalesInvoiceTable.amountDue.isNull() and
                                    (SalesInvoiceTable.taxInclusiveTotal greater BigDecimal.ZERO)
                            )
                    )
            )

    /**
     * A live obligation, lifecycle-wise.
     *
     * `sales_invoice` has no `paid_at` to consult — the AP side's
     * [unpaid][org.example.project.features.document.data.repository.InvoiceResidual.unpaid] has no
     * counterpart — so `status = ISSUED` is what makes a row one we may still be owed on. A DRAFT has
     * consumed no number and is not yet a §14 document; a CANCELLED one has been reversed by a
     * Gutschrift.
     *
     * Kept separate from [stillOpen] for the same reason `unpaid` is: "still owes money" and "has
     * been issued" are different questions, and callers compose them.
     */
    fun issued(): Op<Boolean> = SalesInvoiceTable.status eq SalesInvoiceStatus.ISSUED
}
