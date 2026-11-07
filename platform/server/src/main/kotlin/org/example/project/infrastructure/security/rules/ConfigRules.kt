package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Shop configuration keys — `ConfigurationRoutes.kt`.
 *
 * Every client reads configuration (`CONFIG_READ`); the thirteen `PUT`s are
 * `PermissionOrRole(EXCEPTION_SYSTEM_CONFIG, MANAGER)` and therefore MUST carry
 * `SYSTEM_CONFIG` — the capability that grant maps to — or a cashier holding the grant would
 * silently lose the access the rule gave them. The helper derives this rather than trusting
 * the verb default.
 *
 * Two exceptions sit at their own levels: the ERP integration pair is `Role(ADMIN)` on BOTH
 * verbs (even the GET reveals whether a credential is set and where transits are announced),
 * and the Kassen-Meldung reads are manager-gated, so neither can share `CONFIG_READ`.
 */
object ConfigRules : RuleModule {
    override val name = "Configuration"

    override val pathPrefixes = listOf("/api/v1/config")

    override val allowedCapabilities =
        setOf(
            Capability.CONFIG_READ,
            Capability.CONFIG_WRITE,
            Capability.KASSEN_MELDUNG_READ,
        )

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/v1/config/{shopId}/vat-mapping" to HttpMethod.Get) to Cap(Capability.CONFIG_READ),
            ("/api/v1/config/{shopId}/vat-mapping" to HttpMethod.Put) to Cap(Capability.CONFIG_WRITE),
            ("/api/v1/config/{shopId}/voucher-config" to HttpMethod.Get) to Cap(Capability.CONFIG_READ),
            ("/api/v1/config/{shopId}/voucher-config" to HttpMethod.Put) to Cap(Capability.CONFIG_WRITE),
            ("/api/v1/config/{shopId}/print-settings" to HttpMethod.Get) to Cap(Capability.CONFIG_READ),
            ("/api/v1/config/{shopId}/print-settings" to HttpMethod.Put) to Cap(Capability.CONFIG_WRITE),
            ("/api/v1/config/{shopId}/datev-xml" to HttpMethod.Get) to Cap(Capability.CONFIG_READ),
            ("/api/v1/config/{shopId}/datev-xml" to HttpMethod.Put) to Cap(Capability.CONFIG_WRITE),
            ("/api/v1/config/{shopId}/order-rules" to HttpMethod.Get) to Cap(Capability.CONFIG_READ),
            ("/api/v1/config/{shopId}/order-rules" to HttpMethod.Put) to Cap(Capability.CONFIG_WRITE),
            ("/api/v1/config/{shopId}/billing-rules" to HttpMethod.Get) to Cap(Capability.CONFIG_READ),
            ("/api/v1/config/{shopId}/billing-rules" to HttpMethod.Put) to Cap(Capability.CONFIG_WRITE),
            ("/api/v1/config/{shopId}/browser-stations" to HttpMethod.Get) to Cap(Capability.CONFIG_READ),
            ("/api/v1/config/{shopId}/browser-stations" to HttpMethod.Put) to Cap(Capability.CONFIG_WRITE),
            ("/api/v1/config/{shopId}/web-access" to HttpMethod.Get) to Cap(Capability.CONFIG_READ),
            ("/api/v1/config/{shopId}/web-access" to HttpMethod.Put) to Cap(Capability.CONFIG_WRITE),
            ("/api/v1/config/{shopId}/break-rules" to HttpMethod.Get) to Cap(Capability.CONFIG_READ),
            ("/api/v1/config/{shopId}/break-rules" to HttpMethod.Put) to Cap(Capability.CONFIG_WRITE),
            ("/api/v1/config/{shopId}/document-storage" to HttpMethod.Get) to Cap(Capability.CONFIG_READ),
            ("/api/v1/config/{shopId}/document-storage" to HttpMethod.Put) to Cap(Capability.CONFIG_WRITE),
            ("/api/v1/config/{shopId}/datev-account-mapping" to HttpMethod.Get) to Cap(Capability.CONFIG_READ),
            ("/api/v1/config/{shopId}/datev-account-mapping" to HttpMethod.Put) to Cap(Capability.CONFIG_WRITE),
            ("/api/v1/config/{shopId}/cash-book-transfer-target" to HttpMethod.Get) to Cap(Capability.CONFIG_READ),
            ("/api/v1/config/{shopId}/cash-book-transfer-target" to HttpMethod.Put) to Cap(Capability.CONFIG_WRITE),
            ("/api/v1/config/{shopId}/kassenbuch-beleg-age" to HttpMethod.Get) to Cap(Capability.CONFIG_READ),
            ("/api/v1/config/{shopId}/kassenbuch-beleg-age" to HttpMethod.Put) to Cap(Capability.CONFIG_WRITE),
            ("/api/v1/config/{shopId}/kassen-meldung" to HttpMethod.Get) to Cap(Capability.KASSEN_MELDUNG_READ),
            ("/api/v1/config/{shopId}/kassen-meldung/pdf" to HttpMethod.Get) to Cap(Capability.KASSEN_MELDUNG_READ),
        )
}
