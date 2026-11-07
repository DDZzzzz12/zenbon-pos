package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * The Buchungsbeginn cutover date — `BookkeepingStartRoutes.kt`.
 *
 * `ACCOUNTING_*`: it is the date from which bookkeeping is authoritative, which is an
 * accounting decision rather than shop configuration.
 */
object BookkeepingStartRules : RuleModule {
    override val name = "Buchungsbeginn"

    override val pathPrefixes = listOf("/api/finance")

    override val allowedCapabilities = setOf(Capability.ACCOUNTING_READ, Capability.ACCOUNTING_WRITE)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/finance/bookkeeping-start" to HttpMethod.Get) to Cap(Capability.ACCOUNTING_READ),
            ("/api/finance/bookkeeping-start" to HttpMethod.Put) to Cap(Capability.ACCOUNTING_WRITE),
            ("/api/finance/bookkeeping-start/acknowledge" to HttpMethod.Post) to Cap(Capability.ACCOUNTING_WRITE),
        )
}
