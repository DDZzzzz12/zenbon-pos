package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Zuordnung, match suggestions, Buchungsbeginn and the DATEV export log/cadence —
 * `AllocationRoutes.kt`, `SuggestionRoutes.kt`, `BookkeepingStartRoutes.kt`,
 * `DatevExportLogRoutes.kt`, `DatevExportRoutes.kt`.
 *
 * Strict `_READ`/`_WRITE` rather than a `_MANAGE` tier: this is exactly the domain where a
 * read-only ACCOUNTANT must be expressible in Stage 3.
 */
object AccountingRules : RuleModule {
    override val name = "Accounting"

    override val pathPrefixes = listOf("/api/allocations", "/api/v1/datev")

    override val allowedCapabilities = setOf(Capability.ACCOUNTING_READ, Capability.ACCOUNTING_WRITE)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/v1/datev/export-log" to HttpMethod.Get) to Cap(Capability.ACCOUNTING_READ),
            ("/api/v1/datev/export-log/{id}/download" to HttpMethod.Get) to Cap(Capability.ACCOUNTING_READ),
            ("/api/v1/datev/export/catchup/preview" to HttpMethod.Get) to Cap(Capability.ACCOUNTING_READ),
            ("/api/v1/datev/export/catchup" to HttpMethod.Post) to Cap(Capability.ACCOUNTING_WRITE),
            ("/api/v1/datev/export/ad-hoc" to HttpMethod.Get) to Cap(Capability.ACCOUNTING_READ),
            ("/api/allocations/queue" to HttpMethod.Get) to Cap(Capability.ACCOUNTING_READ),
            ("/api/allocations/open-invoices" to HttpMethod.Get) to Cap(Capability.ACCOUNTING_READ),
            ("/api/allocations/open-sales-invoices" to HttpMethod.Get) to Cap(Capability.ACCOUNTING_READ),
            ("/api/allocations/open-instalments" to HttpMethod.Get) to Cap(Capability.ACCOUNTING_READ),
            ("/api/allocations/telemetry" to HttpMethod.Get) to Cap(Capability.ACCOUNTING_READ),
            ("/api/allocations" to HttpMethod.Post) to Cap(Capability.ACCOUNTING_WRITE),
            ("/api/allocations/batch" to HttpMethod.Post) to Cap(Capability.ACCOUNTING_WRITE),
            ("/api/allocations/{id}" to HttpMethod.Delete) to Cap(Capability.ACCOUNTING_WRITE),
            ("/api/allocations/suggestions" to HttpMethod.Get) to Cap(Capability.ACCOUNTING_READ),
        )
}
