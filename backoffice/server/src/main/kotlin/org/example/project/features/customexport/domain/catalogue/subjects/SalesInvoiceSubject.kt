package org.example.project.features.customexport.domain.catalogue.subjects

import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.features.customexport.domain.catalogue.ExportSubject
import org.example.project.features.customexport.domain.catalogue.ExportToggle
import org.example.project.features.salesinvoice.data.repository.SalesInvoiceResidual
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq

/**
 * Ausgangsrechnungen — the invoices **we** issued: what a shop hands its tax advisor for the revenue
 * side, and what it works through when chasing what a customer still owes.
 *
 * The root is [SalesInvoiceTable] and every related table is derived, exactly as on the AP side. The
 * chain the graph finds mirrors [IncomingInvoiceSubject]'s with the roles inverted: an invoice points
 * at a **debtor**, which is the customer *role*, and the role points at a **business partner**, which
 * is the company. `buyer_name` on the invoice itself is a third thing again — the snapshot we printed
 * on that one document, deliberately frozen so a customer who moves does not silently reprint a 2027
 * invoice with a 2029 address. All three are offered as their own groups, and which an operator ticks
 * is their choice.
 *
 * Two more tables come along for free, and they are the ones [SchemaGraph][
 * org.example.project.features.customexport.domain.catalogue.SchemaGraph]'s depth limit kept out of
 * reach until this subject existed: `sales_invoice_booking` (to-one, `uniqueIndex(salesInvoiceId)`)
 * as ordinary columns, and `sales_invoice_line` / `sales_invoice_vat_breakdown` (to-many) as
 * aggregates only. From the partner root they sit three hops out; from here they are one.
 *
 * ## The shop column, unlike the AP side
 *
 * [SalesInvoiceTable.shopId] is NOT NULL, so this subject declares it and the runner narrows every
 * run to the current shop. That is the difference from [IncomingInvoiceSubject], which has to leave
 * [ExportSubject.shopColumn] null because nothing in the *incoming* invoice chain carries a shop.
 * Here the column is on the root itself and there is no reason to omit it.
 *
 * ## The three toggles
 *
 * - **Nur gestellte** — `status = ISSUED`. A draft has consumed no invoice number and is not yet a
 *   §14 document; a cancellation has been reversed by a Gutschrift.
 * - **Ohne stornierte** — `status <> CANCELLED`. The looser one, for an operator who wants the drafts
 *   in the file but not the reversed rows.
 * - **Nur offene** — [SalesInvoiceResidual.stillOpen] on an [issued][SalesInvoiceResidual.issued]
 *   invoice. A draft is not open, it is a draft.
 *
 * "Nur offene" is safe here for one reason only: it calls the single shared definition of what an
 * outgoing invoice still owes, the same one `SalesInvoiceRepositoryImpl.list` and
 * `AllocationQueueRepositoryImpl.openSalesInvoices` call, so the export cannot disagree with the
 * Rechnungsübersicht about which invoices are open. Writing the cascade out here instead would be
 * precisely the mistake `InvoiceResidual`'s KDoc records: `open_amount` is nullable and unset on
 * whole classes of rows, `amount_due` and `tax_inclusive_total` are the fallbacks, and a naive
 * `open_amount > 0` reports a customer who owes thousands as owing nothing — while the file looks
 * perfectly healthy, because the rows are there and the total is merely zero. On the AP side that
 * produced two screens disagreeing about the same seven invoices. Do not inline it.
 *
 * ## No base predicate
 *
 * Nothing is excluded unconditionally. A cancelled invoice is still an invoice that existed and still
 * carries a number out of the §14 sequence; dropping it by default would be the silent omission this
 * whole feature is built to avoid — a shorter file looks like a shorter file, not like a filter
 * nobody asked for. The operator says so with the toggle above instead. This differs from
 * [PartnerSubject], whose merged-away party has genuinely stopped being a company.
 *
 * [ExportSubject.alwaysJoined] is empty for the matching reason: every toggle reads columns of the
 * root — `status`, `open_amount`, `amount_due`, `tax_inclusive_total` — so no related table has to be
 * in the query before the filter is built.
 *
 * Every field is gated by [Capability.INVOICE_READ], which is what `InvoiceRules` requires of
 * `GET /api/v1/sales-invoices`: an export must never be an easier way to read data than the screen
 * showing it.
 */
object SalesInvoiceSubject {
    val subject: ExportSubject =
        ExportSubject(
            key = "sales_invoice",
            label = "Ausgangsrechnungen",
            root = SalesInvoiceTable,
            // No cast, unlike the AP side's `issueDate`: `sales_invoice.issue_date` is NOT NULL, so
            // it already is the non-null type this parameter wants.
            dateColumn = SalesInvoiceTable.issueDate,
            shopColumn = SalesInvoiceTable.shopId,
            toggles =
                listOf(
                    ExportToggle(
                        key = "sales_invoice.issued_only",
                        label = "Nur gestellte",
                        predicate = { SalesInvoiceTable.status eq SalesInvoiceStatus.ISSUED },
                    ),
                    ExportToggle(
                        key = "sales_invoice.exclude_cancelled",
                        label = "Ohne stornierte",
                        predicate = { SalesInvoiceTable.status neq SalesInvoiceStatus.CANCELLED },
                    ),
                    ExportToggle(
                        key = "sales_invoice.open_only",
                        label = "Nur offene",
                        predicate = { SalesInvoiceResidual.stillOpen() and SalesInvoiceResidual.issued() },
                    ),
                ),
            basePredicate = null,
            alwaysJoined = emptyList(),
        )
}
