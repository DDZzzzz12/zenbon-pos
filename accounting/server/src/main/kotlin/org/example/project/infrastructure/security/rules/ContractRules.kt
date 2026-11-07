package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Contracts and recurring obligations — `ContractRoutes.kt`, `ContractAlertRoutes.kt`.
 */
object ContractRules : RuleModule {
    override val name = "Contracts"

    override val pathPrefixes = listOf("/api/contracts")

    override val allowedCapabilities = setOf(Capability.CONTRACT_READ, Capability.CONTRACT_WRITE)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/contracts" to HttpMethod.Get) to Cap(Capability.CONTRACT_READ),
            ("/api/contracts/alerts" to HttpMethod.Get) to Cap(Capability.CONTRACT_READ),
            ("/api/contracts/{id}" to HttpMethod.Get) to Cap(Capability.CONTRACT_READ),
            ("/api/contracts" to HttpMethod.Post) to Cap(Capability.CONTRACT_WRITE),
            ("/api/contracts/{id}" to HttpMethod.Put) to Cap(Capability.CONTRACT_WRITE),
            ("/api/contracts/{id}/instalments" to HttpMethod.Get) to Cap(Capability.CONTRACT_READ),
        )
}
