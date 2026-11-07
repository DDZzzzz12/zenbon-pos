package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Suppliers, our own bank accounts and imported bank statements — `SupplierRoutes.kt`,
 * `BankAccountRoutes.kt`, bank-statement routes.
 */
object CreditorRules : RuleModule {
    override val name = "Creditors"

    override val pathPrefixes = listOf("/api/suppliers", "/api/bank-accounts", "/api/bank-statements")

    override val allowedCapabilities = setOf(Capability.CREDITOR_READ, Capability.CREDITOR_WRITE)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/suppliers" to HttpMethod.Get) to Cap(Capability.CREDITOR_READ),
            ("/api/suppliers/{id}" to HttpMethod.Get) to Cap(Capability.CREDITOR_READ),
            ("/api/suppliers" to HttpMethod.Post) to Cap(Capability.CREDITOR_WRITE),
            ("/api/suppliers/{id}" to HttpMethod.Put) to Cap(Capability.CREDITOR_WRITE),
            ("/api/bank-accounts" to HttpMethod.Get) to Cap(Capability.CREDITOR_READ),
            ("/api/bank-accounts/{id}" to HttpMethod.Get) to Cap(Capability.CREDITOR_READ),
            ("/api/bank-accounts/{id}/statements" to HttpMethod.Get) to Cap(Capability.CREDITOR_READ),
            ("/api/bank-accounts/{id}/statements/{statementId}/transactions" to HttpMethod.Get) to Cap(Capability.CREDITOR_READ),
            ("/api/bank-accounts" to HttpMethod.Post) to Cap(Capability.CREDITOR_WRITE),
            ("/api/bank-accounts/resolve" to HttpMethod.Post) to Cap(Capability.CREDITOR_WRITE),
            ("/api/bank-statements/{id}/account" to HttpMethod.Put) to Cap(Capability.CREDITOR_WRITE),
        )
}
