package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Card payment terminals — `CardTerminalRoutes.kt`.
 *
 * Three access levels, not two, so this family needs three capabilities:
 *  - the till lists its card terminals and pings their status — `CARD_TERMINAL_READ`;
 *  - a manager connects, disconnects, runs diagnosis and previews totals —
 *    `CARD_TERMINAL_OPERATE`;
 *  - only an admin provisions them — `CARD_TERMINAL_MANAGE`.
 *
 * `POST /{id}/status` is a status ping rather than a mutation and stays at till level.
 */
object CardTerminalRules : RuleModule {
    override val name = "Card terminals"

    override val pathPrefixes = listOf("/api/card-terminals")

    override val allowedCapabilities = setOf(Capability.CARD_TERMINAL_MANAGE, Capability.CARD_TERMINAL_OPERATE, Capability.CARD_TERMINAL_READ)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/card-terminals" to HttpMethod.Get) to Cap(Capability.CARD_TERMINAL_READ),
            ("/api/card-terminals/available" to HttpMethod.Get) to Cap(Capability.CARD_TERMINAL_READ),
            ("/api/card-terminals/{id}" to HttpMethod.Get) to Cap(Capability.CARD_TERMINAL_READ),
            ("/api/card-terminals" to HttpMethod.Post) to Cap(Capability.CARD_TERMINAL_MANAGE),
            ("/api/card-terminals/{id}" to HttpMethod.Put) to Cap(Capability.CARD_TERMINAL_MANAGE),
            ("/api/card-terminals/{id}" to HttpMethod.Delete) to Cap(Capability.CARD_TERMINAL_MANAGE),
            ("/api/card-terminals/{id}/connect" to HttpMethod.Post) to Cap(Capability.CARD_TERMINAL_OPERATE),
            ("/api/card-terminals/{id}/disconnect" to HttpMethod.Post) to Cap(Capability.CARD_TERMINAL_OPERATE),
            ("/api/card-terminals/{id}/status" to HttpMethod.Post) to Cap(Capability.CARD_TERMINAL_READ),
            ("/api/card-terminals/{id}/diagnosis" to HttpMethod.Post) to Cap(Capability.CARD_TERMINAL_OPERATE),
            ("/api/card-terminals/{id}/totals-preview" to HttpMethod.Get) to Cap(Capability.CARD_TERMINAL_OPERATE),
        )
}
