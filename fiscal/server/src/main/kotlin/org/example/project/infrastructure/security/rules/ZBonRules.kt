package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Z-Bon daily-close reports — `ZBonRoutes.kt`.
 *
 * The till reads the Z-Bon list and the last-close timestamp to know whether the day is open
 * (`ZBON_READ`). Individual reports, history, PDFs and reprints are back office
 * (`ZBON_MANAGE`). Creating one is `ZBON_CLOSE`, RESERVED because it produces the fiscal
 * daily-close document.
 *
 * Paying an employee's tips out of a closing (`/{id}/tip-payouts`) is `CASHBOOK_WRITE`: what it
 * writes is a cash withdrawal from the fiscal Ladenkasse, and `POST /api/v1/kassenbuch` — where any
 * other withdrawal is booked — is gated on exactly that. Gating it on a Z-Bon capability instead
 * would let a holder of `ZBON_MANAGE` take cash out of the drawer without being trusted with the
 * cash book. Those are the only non-`ZBON_*` capabilities this module names. The same holds for the
 * payouts booked INSIDE a closing (F58): `POST /api/z-bon` stays `ZBON_CLOSE`, and the service
 * refuses a request carrying `tipPayouts` without `CASHBOOK_WRITE` (403) — the route table cannot
 * express "only when the body asks for it". `/open-period-tips` lists what such a payout may take;
 * it books nothing, so it is the cash book's READ capability, like the closing dialog's other
 * lookups (`/api/v1/kassenbuch/cash-count/expected`, `/api/v1/kassenbuch/books`). Paying out stays
 * `CASHBOOK_WRITE` on the POSTs and in the service, and the till only shows the payout section to
 * holders of `CASHBOOK_WRITE` (`mayPayOutTips`).
 */
object ZBonRules : RuleModule {
    override val name = "Z-Bon"

    override val pathPrefixes = listOf("/api/z-bon")

    override val allowedCapabilities =
        setOf(
            Capability.ZBON_CLOSE,
            Capability.ZBON_MANAGE,
            Capability.ZBON_READ,
            Capability.CASHBOOK_READ,
            Capability.CASHBOOK_WRITE,
        )

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/z-bon" to HttpMethod.Get) to Cap(Capability.ZBON_READ),
            ("/api/z-bon/recent" to HttpMethod.Get) to Cap(Capability.ZBON_MANAGE),
            ("/api/z-bon/{id}" to HttpMethod.Get) to Cap(Capability.ZBON_MANAGE),
            ("/api/z-bon/history" to HttpMethod.Get) to Cap(Capability.ZBON_MANAGE),
            ("/api/z-bon/latest/datetime" to HttpMethod.Get) to Cap(Capability.ZBON_READ),
            ("/api/z-bon" to HttpMethod.Post) to Cap(Capability.ZBON_CLOSE),
            ("/api/z-bon/{id}/pdf" to HttpMethod.Get) to Cap(Capability.ZBON_MANAGE),
            ("/api/z-bon/{id}/print" to HttpMethod.Post) to Cap(Capability.ZBON_MANAGE),
            ("/api/z-bon/closing-readiness" to HttpMethod.Get) to Cap(Capability.ZBON_CLOSE),
            ("/api/z-bon/open-period-tips" to HttpMethod.Get) to Cap(Capability.CASHBOOK_READ),
            ("/api/z-bon/{id}/tip-payouts" to HttpMethod.Post) to Cap(Capability.CASHBOOK_WRITE),
            ("/api/z-bon/{id}/tip-payouts/{payoutHeaderId}/reverse" to HttpMethod.Post) to Cap(Capability.CASHBOOK_WRITE),
            // F59: a bank/payroll settlement's storno — the same capability as the payout it reverses.
            ("/api/z-bon/{id}/tip-payouts/settlements/{payoutId}/reverse" to HttpMethod.Post) to Cap(Capability.CASHBOOK_WRITE),
        )
}
