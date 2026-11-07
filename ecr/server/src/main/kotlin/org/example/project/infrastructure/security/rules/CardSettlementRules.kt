package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Card settlement records — `CardSettlementRoutes.kt`.
 *
 * `ACCOUNTING_*` rather than `CARD_TERMINAL_*`: a settlement is a financial reconciliation
 * record, not terminal hardware, and it is the accountant who reads it.
 */
object CardSettlementRules : RuleModule {
    override val name = "Card settlements"

    override val pathPrefixes = listOf("/api/card-settlements")

    override val allowedCapabilities = setOf(Capability.ACCOUNTING_READ)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/card-settlements" to HttpMethod.Get) to Cap(Capability.ACCOUNTING_READ),
            ("/api/card-settlements/{id}/reconciliation" to HttpMethod.Get) to Cap(Capability.ACCOUNTING_READ),
        )
}
