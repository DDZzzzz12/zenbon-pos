package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * The normalized invoice aggregate, incoming and outgoing — `InvoiceRoutes.kt`,
 * `SalesInvoiceRoutes.kt`.
 *
 * Strict `_READ`/`_WRITE`: a read-only ACCOUNTANT reading invoices without being able to issue
 * or edit them is the motivating case for this whole arc.
 *
 * The two DATEV export routes carry `DEBTOR_DATEV_EXPORT`: they are `Role(ADMIN)` while the rest
 * of the family is manager-gated, and the obvious `DATEV_EXPORT` is reachable through the dead
 * `EXCEPTION_EXPORT_DATA` grant, which would have opened them to every grant holder.
 */
object InvoiceRules : RuleModule {
    override val name = "Invoices"

    override val pathPrefixes = listOf("/api/invoices", "/api/v1/sales-invoices")

    override val allowedCapabilities = setOf(Capability.DEBTOR_DATEV_EXPORT, Capability.INVOICE_READ, Capability.INVOICE_WRITE)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/v1/sales-invoices" to HttpMethod.Get) to Cap(Capability.INVOICE_READ),
            ("/api/v1/sales-invoices/from-bill" to HttpMethod.Post) to Cap(Capability.INVOICE_WRITE),
            ("/api/v1/sales-invoices/standalone" to HttpMethod.Post) to Cap(Capability.INVOICE_WRITE),
            ("/api/v1/sales-invoices/{id}" to HttpMethod.Get) to Cap(Capability.INVOICE_READ),
            ("/api/v1/sales-invoices/{id}" to HttpMethod.Put) to Cap(Capability.INVOICE_WRITE),
            ("/api/v1/sales-invoices/{id}" to HttpMethod.Delete) to Cap(Capability.INVOICE_WRITE),
            ("/api/v1/sales-invoices/{id}/issue" to HttpMethod.Post) to Cap(Capability.INVOICE_WRITE),
            ("/api/v1/sales-invoices/{id}/settle-at-till" to HttpMethod.Post) to Cap(Capability.INVOICE_WRITE),
            ("/api/v1/sales-invoices/{id}/cancel" to HttpMethod.Post) to Cap(Capability.INVOICE_WRITE),
            ("/api/v1/sales-invoices/{id}/pdf" to HttpMethod.Get) to Cap(Capability.INVOICE_READ),
            ("/api/v1/sales-invoices/export/datev" to HttpMethod.Get) to Cap(Capability.DEBTOR_DATEV_EXPORT),
            ("/api/v1/sales-invoices/export/datev/statistics" to HttpMethod.Get) to Cap(Capability.DEBTOR_DATEV_EXPORT),
            ("/api/invoices" to HttpMethod.Get) to Cap(Capability.INVOICE_READ),
            ("/api/invoices/{id}" to HttpMethod.Get) to Cap(Capability.INVOICE_READ),
            ("/api/invoices/{id}" to HttpMethod.Put) to Cap(Capability.INVOICE_WRITE),
            ("/api/invoices/{id}/booking" to HttpMethod.Put) to Cap(Capability.INVOICE_WRITE),
            ("/api/invoices/{id}/review" to HttpMethod.Put) to Cap(Capability.INVOICE_WRITE),
            ("/api/invoices/{id}/cancel" to HttpMethod.Post) to Cap(Capability.INVOICE_WRITE),
        )
}
