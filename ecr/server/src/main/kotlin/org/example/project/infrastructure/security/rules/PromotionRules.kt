package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Promotions — `PromotionRoutes.kt`.
 *
 * Only `GET /api/promotions` (the active list the till prices against) is till work. Every other
 * read — the full list, a single promotion, its usage report — is manager-gated today, which is
 * exactly the shape the `_MANAGE` tier exists for.
 */
object PromotionRules : RuleModule {
    override val name = "Promotions"

    override val pathPrefixes = listOf("/api/promotions")

    override val allowedCapabilities = setOf(Capability.PROMOTION_MANAGE, Capability.PROMOTION_READ)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/promotions" to HttpMethod.Get) to Cap(Capability.PROMOTION_READ),
            ("/api/promotions/all" to HttpMethod.Get) to Cap(Capability.PROMOTION_MANAGE),
            ("/api/promotions/{id}" to HttpMethod.Get) to Cap(Capability.PROMOTION_MANAGE),
            ("/api/promotions" to HttpMethod.Post) to Cap(Capability.PROMOTION_MANAGE),
            ("/api/promotions/{id}" to HttpMethod.Put) to Cap(Capability.PROMOTION_MANAGE),
            ("/api/promotions/{id}" to HttpMethod.Delete) to Cap(Capability.PROMOTION_MANAGE),
            ("/api/promotions/{id}/usage" to HttpMethod.Get) to Cap(Capability.PROMOTION_MANAGE),
        )
}
