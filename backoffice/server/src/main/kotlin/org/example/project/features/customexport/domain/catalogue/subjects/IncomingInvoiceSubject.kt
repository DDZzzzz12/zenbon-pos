package org.example.project.features.customexport.domain.catalogue.subjects

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.customexport.domain.catalogue.ExportSubject
import org.example.project.features.customexport.domain.catalogue.ExportToggle
import org.example.project.features.customexport.domain.catalogue.SchemaGraph
import org.example.project.features.document.data.repository.InvoiceResidual
import org.example.project.features.document.database.tables.InvoiceBookingTable
import org.example.project.features.document.database.tables.InvoiceTable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.and

/**
 * Eingangsrechnungen — the supplier invoices an operator hands their tax advisor, or works through
 * when chasing what is still open.
 *
 * The root is [InvoiceTable] and every related table is derived. The chain the graph finds is the
 * model's own: an invoice points at a **supplier**, which is the creditor *role*, and the role points
 * at a **business partner**, which is the company. The name an operator means by "Lieferant" is the
 * party's, not the role's — the role holds only what is specific to being a creditor (Kreditorenkonto,
 * payment terms), and the invoice's own `seller_name` holds only what the supplier asserted on that
 * one document. All three are offered as their own groups; which one an operator ticks is their
 * choice, and it used to be ours.
 *
 * `invoice_booking` is the exception, declared in [ExportSubject.alwaysJoined]: that is where *our*
 * payment decision lives, and the "Nur offene" toggle reads it whether or not a booking column was
 * selected. It is picked out of the graph rather than restated as a join, so there is still only one
 * statement of how the two tables meet.
 *
 * ## Why every join is a LEFT join
 *
 * `invoice.supplier_id` is nullable: an invoice that has arrived but has not yet been resolved to a
 * creditor has none. An inner join would silently drop exactly those rows — and they are the ones an
 * operator is most likely to be chasing, because an unresolved invoice is unresolved *work*. Worse,
 * the omission is invisible in the file: a shorter list looks like a shorter list, not like a filter
 * nobody asked for. So the join is left, and an unresolved invoice appears with a blank Lieferant and
 * a blank Kreditorennummer, which is a truthful rendering of what we know about it. `RelationHop`
 * makes that the only kind of join a path can produce, so it is no longer a choice this subject can
 * get wrong.
 *
 * `invoice_booking` is left-joined for the same reason in a different direction: an invoice with no
 * booking row at all is unpaid, and `InvoiceResidual.unpaid()` reads that as a NULL `paid_at`, which
 * only a left join can produce. Neither join can multiply rows — `supplier_id` is a single reference
 * and `invoice_booking.invoice_id` is uniquely indexed, which is what makes the graph call both paths
 * [org.example.project.features.customexport.domain.catalogue.Cardinality.ONE] and therefore offer
 * them at all.
 *
 * ## Why there is no shop column
 *
 * [InvoiceTable] carries none, and neither does `documents` behind it. Nothing in the invoice chain
 * is shop-scoped: the only shop reference anywhere near it is `business_partner.shop_id`, one hop
 * away through the creditor role and absent on precisely the unresolved invoices the left join exists
 * to keep. Declaring *that* column as [ExportSubject.shopColumn] would therefore not scope the export
 * so much as re-impose the inner join through the WHERE clause, since `NULL = 1` is not true.
 *
 * So [ExportSubject.shopColumn] is null, stated here rather than left to be discovered: what scopes a
 * supplier invoice to a shop today is the **installation** — one server, one shop's documents — the
 * same single-shop assumption `SupplierRepositoryImpl.create` and `CustomExportServiceImpl.currentShop`
 * already make. Multi-shop work has to give the invoice a shop of its own before this subject can
 * declare one, and it should find this note when it does.
 *
 * ## No base predicate
 *
 * [PartnerSubject] has one because a merged-away party has stopped being a company. An invoice has no
 * equivalent state: every row in `invoice` is an invoice we received, including the unresolved, the
 * unreviewed and the already-paid. Which of those an operator wants is their choice, expressed by the
 * toggle and the date range, not ours to make for them.
 *
 * Every field is gated by [Capability.CREDITOR_READ], the same capability the Rechnungen and
 * Lieferanten screens require: an export must never be an easier way to read data than the screen
 * showing it.
 */
object IncomingInvoiceSubject {
    /**
     * The date the range filter applies to.
     *
     * `issue_date` is nullable, and [ExportSubject.dateColumn] is typed non-null because
     * [PartnerSubject]'s `created_at` is. The cast is erased at runtime and the runner only ever
     * builds `>=` / `<=` against the column, so the effect on an invoice with no issue date is the
     * one we want: it satisfies neither bound and drops out of a date-ranged export, because there is
     * no date on which to claim it falls inside the operator's range. Undated invoices still export
     * fine with no range set.
     *
     * Declared **before** [subject]: an `object`'s properties initialise top to bottom, so a
     * reference from [subject] to a val written below it would read null.
     */
    @Suppress("UNCHECKED_CAST")
    private val issueDate = InvoiceTable.issueDate as Column<LocalDateTime>

    val subject: ExportSubject =
        ExportSubject(
            key = "invoice",
            label = "Eingangsrechnungen",
            root = InvoiceTable,
            dateColumn = issueDate,
            shopColumn = null,
            toggles =
                listOf(
                    ExportToggle(
                        key = "invoice.unpaid_only",
                        label = "Nur offene",
                        // Reuses `InvoiceResidual` rather than restating it. The definition is subtle —
                        // `open_amount` is NULL on every invoice that arrived through the e-invoice
                        // pipeline, so a predicate reading that column alone reports a supplier we owe
                        // thousands as owing nothing — and this codebase has already shipped two
                        // screens that disagreed about the same seven invoices for exactly that
                        // reason. A third spelling of it here would be the same bug a third time.
                        predicate = { InvoiceResidual.stillOpen() and InvoiceResidual.unpaid() },
                    ),
                ),
            basePredicate = null,
            // The toggle above reads `invoice_booking.paid_at`, so that table has to be in the query
            // before the filter is built — a path joined on demand would not be there when no booking
            // column was ticked.
            alwaysJoined =
                SchemaGraph
                    .pathsFrom(InvoiceTable)
                    .filter { it.target == InvoiceBookingTable },
        )
}
