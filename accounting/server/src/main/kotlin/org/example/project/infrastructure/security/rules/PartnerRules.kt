package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Business partners — `PartnerRoutes.kt`.
 *
 * Reading is a creditor-side read: the duplicate list is reached from the creditor screens, and a
 * bookkeeper who may see creditors may see the companies behind them.
 *
 * **Merging requires both write capabilities.** It re-points a creditor role and a debtor role in
 * one operation, so holding one side must not be enough to restructure the other — see the business
 * partner design spec, §4.
 *
 * **Exporting the Debitoren-/Kreditorenstamm requires both read capabilities.** The file carries
 * creditor *and* debtor master data in one download, so being trusted with one side is not being
 * trusted with the whole handover — see the business partner design spec, §4. This is the second use
 * of `AllOf` after the merge.
 *
 * `/api/partners/duplicates` and `/api/partners/merge` are literal paths, so they land in the
 * registry's exact map and are resolved before `/api/partners/{id}` is ever pattern-matched — see
 * `RoutePermissions.findRule`. The `{id}` rule would also match them, which is why the two GET rules
 * deliberately name the same capability: were the exact-first ordering ever to change, the fallback
 * verdict stays identical. `/merge` is a POST and so cannot collide with the GET-only `{id}` rule.
 *
 * `/api/partners/export/datev-stammdaten` needs no such hedge. It is exact too, but it is also two
 * segments where `{id}` is one, and `ParameterizedRule.matches` rejects on segment count before it
 * ever compares a placeholder — so `{id}` cannot swallow it even if the exact map were consulted
 * second. The stricter `AllOf` verdict is therefore reached by the only rule that can match it.
 */
object PartnerRules : RuleModule {
    override val name = "Partners"

    override val pathPrefixes = listOf("/api/partners")

    override val allowedCapabilities =
        setOf(
            Capability.CREDITOR_READ,
            Capability.CREDITOR_WRITE,
            Capability.DEBTOR_READ,
            Capability.DEBTOR_WRITE,
        )

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/partners/duplicates" to HttpMethod.Get) to Cap(Capability.CREDITOR_READ),
            ("/api/partners/{id}" to HttpMethod.Get) to Cap(Capability.CREDITOR_READ),
            ("/api/partners/merge" to HttpMethod.Post) to
                AccessLevel.AllOf(setOf(Capability.CREDITOR_WRITE, Capability.DEBTOR_WRITE)),
            ("/api/partners/export/datev-stammdaten" to HttpMethod.Get) to
                AccessLevel.AllOf(setOf(Capability.CREDITOR_READ, Capability.DEBTOR_READ)),
        )
}
