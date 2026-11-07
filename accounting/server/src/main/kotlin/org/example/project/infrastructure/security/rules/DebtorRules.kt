package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Debtors — the counterparties of outgoing invoices — `DebtorRoutes.kt`.
 */
object DebtorRules : RuleModule {
    override val name = "Debtors"

    override val pathPrefixes = listOf("/api/debtors")

    override val allowedCapabilities = setOf(Capability.DEBTOR_READ, Capability.DEBTOR_WRITE)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/debtors" to HttpMethod.Get) to Cap(Capability.DEBTOR_READ),
            ("/api/debtors/{id}" to HttpMethod.Get) to Cap(Capability.DEBTOR_READ),
            ("/api/debtors" to HttpMethod.Post) to Cap(Capability.DEBTOR_WRITE),
            ("/api/debtors/{id}" to HttpMethod.Put) to Cap(Capability.DEBTOR_WRITE),
            ("/api/debtors/{id}" to HttpMethod.Delete) to Cap(Capability.DEBTOR_WRITE),
        )
}
