package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Customers and their addresses — `CustomerRoutes.kt`, `CustomerAddressRoutes.kt`.
 *
 * Every rule here was `Authenticated` before the migration, so Stage 1 gave the whole module
 * `CUSTOMER_READ`/`CUSTOMER_WRITE` and put both in `TERMINAL_DEVICE` — defect D4's largest piece:
 * a terminal login could enumerate the entire customer database.
 *
 * **Stage 2b splits enumeration away from lookup.** The till searches for a specific person and
 * attaches them to a table session, so `search`, `{id}` and the address-by-customer read keep
 * `CUSTOMER_READ`, and creating a customer at the till keeps `CUSTOMER_WRITE`. The two unrestricted
 * list routes move to `CUSTOMER_LIST`, which only the back-office groups hold. The distinction is a
 * GDPR one as much as a security one: looking someone up is till work, walking the whole base is not.
 */
object CustomerRules : RuleModule {
    override val name = "Customers"

    override val pathPrefixes = listOf("/api/customers", "/api/customer-addresses")

    override val allowedCapabilities = setOf(Capability.CUSTOMER_LIST, Capability.CUSTOMER_READ, Capability.CUSTOMER_WRITE)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            // Customers — CustomerRoutes.kt, route("/api/customers")
            ("/api/customers" to HttpMethod.Get) to Cap(Capability.CUSTOMER_LIST),
            ("/api/customers/search" to HttpMethod.Get) to Cap(Capability.CUSTOMER_READ),
            ("/api/customers/{id}" to HttpMethod.Get) to Cap(Capability.CUSTOMER_READ),
            ("/api/customers" to HttpMethod.Post) to Cap(Capability.CUSTOMER_WRITE),
            // Customer Addresses — CustomerAddressRoutes.kt, route("/api/customer-addresses")
            ("/api/customer-addresses" to HttpMethod.Get) to Cap(Capability.CUSTOMER_LIST),
            ("/api/customer-addresses/customer/{customerId}" to HttpMethod.Get) to Cap(Capability.CUSTOMER_READ),
            ("/api/customer-addresses" to HttpMethod.Post) to Cap(Capability.CUSTOMER_WRITE),
        )
}
