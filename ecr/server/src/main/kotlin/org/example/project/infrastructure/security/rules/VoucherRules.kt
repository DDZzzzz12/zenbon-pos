package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Vouchers and redemption history — `VoucherRoutes.kt`.
 *
 * Every rule here was `Authenticated`, so Stage 1 gave all of them `VOUCHER_READ`/`VOUCHER_WRITE`
 * and the whole module landed in `TERMINAL_DEVICE` — defect D4.
 *
 * **Stage 2b splits the module along the line the routes actually fall on.** `VOUCHER_READ` is the
 * till's work: validate a code, read one voucher's redemption history. `VOUCHER_REPORT` is shop-wide
 * reporting — statistics, the two by-date rollups, the active list and the number search — which a
 * terminal has no business reading.
 * This is the same `_READ`/`_MANAGE` split Stage 1 applied to promotions and Z-Bon; the difference is
 * that here it is a deliberate behaviour change, not an equivalence-preserving one, and the three
 * routes it moves are enumerated in `TerminalBundleTrimTest`.
 *
 * `POST /api/vouchers/validate` deliberately carries `VOUCHER_READ`: it looks a voucher up
 * and mutates nothing — the verb is POST only to keep the code out of the URL. It is listed
 * as an exemption in `ReadWriteSanityTest`.
 */
object VoucherRules : RuleModule {
    override val name = "Vouchers"

    override val pathPrefixes = listOf("/api/vouchers")

    override val allowedCapabilities = setOf(Capability.VOUCHER_REPORT, Capability.VOUCHER_READ, Capability.VOUCHER_WRITE)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/vouchers/statistics" to HttpMethod.Get) to Cap(Capability.VOUCHER_REPORT),
            ("/api/vouchers/created-by-date" to HttpMethod.Get) to Cap(Capability.VOUCHER_REPORT),
            ("/api/vouchers/redeemed-by-date" to HttpMethod.Get) to Cap(Capability.VOUCHER_REPORT),
            // The back office's voucher list and number search (restored after 747923678 dropped them as
            // "no client caller" while adminApp's Gutscheine screen was calling both). Shop-wide, so REPORT.
            ("/api/vouchers/active" to HttpMethod.Get) to Cap(Capability.VOUCHER_REPORT),
            ("/api/vouchers/search" to HttpMethod.Get) to Cap(Capability.VOUCHER_REPORT),
            ("/api/vouchers/number/{voucherNumber}/redemption-history" to HttpMethod.Get) to Cap(Capability.VOUCHER_READ),
            ("/api/vouchers" to HttpMethod.Post) to Cap(Capability.VOUCHER_WRITE),
            ("/api/vouchers/validate" to HttpMethod.Post) to Cap(Capability.VOUCHER_READ),
        )
}
