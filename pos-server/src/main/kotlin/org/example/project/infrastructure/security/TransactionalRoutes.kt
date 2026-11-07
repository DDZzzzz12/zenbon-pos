/**
 * Exact (method, route) transactional-route matcher for the terminal-capability ceiling
 * (Terminal Phase A4, Task 3).
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.infrastructure.security

import io.ktor.http.HttpMethod

/**
 * Exact (method, route) pairs that reach a TSE `signTransaction`/`enqueue` call UNDER THE CALLING
 * TERMINAL'S OWN CLIENT (per-station sales transactions). A non-fiscal terminal is refused ALL of
 * these; a fiscal-but-unregistered terminal is refused while TSE mode is NORMAL. `{seg}` matches
 * exactly one path segment.
 *
 * This is an ALLOW-BY-EXACT-MATCH set, not a prefix match — a prefix match on `/api/v1/bills`
 * would wrongly catch `/api/v1/bills/{id}/print` (no TSE signing) and would need separate handling
 * for the `DELETE` verb, so instead every entry is matched on method + segment count + literal
 * (or `{wildcard}`) equality per segment.
 *
 * Verified against the real sign call sites (`signTransaction(` / `tseSigningQueueService.enqueue(`)
 * as of A4 Task 3, extended for the MAJOR ceiling-bypass fix below:
 *  - `OrderServiceImpl.createOrder`      -> POST /api/orders
 *  - `OrderItemServiceImpl.cancelOrderItems` -> POST /api/items/cancel/{sourceTableId}
 *  - `BillServiceImpl.createBill`        -> POST /api/v1/bills
 *  - `BillServiceImpl.createBill` (2nd caller) -> GET /api/bills/create-with-card
 *    (`CardPaymentSseRoutes` — note the path is `/api/bills/...`, NOT `/api/v1/bills`; this route
 *    was previously missing from the set entirely, letting a deactivated/non-fiscal/unregistered
 *    terminal reach `BillServiceImpl.createBill` and sign a RECHNUNG without ever passing the
 *    ceiling. `TransactionSigningGuard.assertMaySign` is the sole gate for any caller not covered
 *    here — keep both in sync, but never rely on the guard alone as a substitute for listing the
 *    route.)
 *  - `BillServiceImpl.deleteBill`        -> DELETE /api/v1/bills/{id}
 *  - `BillServiceImpl.rebookBill`        -> POST /api/v1/bills/{id}/rebook
 *
 * NOT in this set: `ZBonServiceImpl.createReport`, `CashBookTransferServiceImpl` and
 * `KassenbuchServiceImpl` (`createSignedEntry` / `createCorrection`). They also sign, and — contrary
 * to what this KDoc claimed until Stage 4b — they sign under the CALLING STATION's own TSE client,
 * exactly like the routes listed above. The old rationale ("register-level documents, not
 * per-terminal transactions attributed to the calling station's own client") was factually wrong
 * about its own code, and it also claimed a `Role(MANAGER)` gate that Stage 1 had already replaced
 * with capabilities.
 *
 * They stay out of this set for a different and real reason: **their guard is data-dependent.**
 * `KassenbuchServiceImpl` signs only when the target cash book is fiscal (`targetBookIsFiscal`), and
 * `CashBookTransferServiceImpl` only for whichever leg is fiscal. A static route set cannot express
 * "gated only when the row being written is fiscal" — that is the third gate kind the parent spec
 * §2.1 names, a FISCAL PRECONDITION, and it necessarily lives in the service.
 *
 * What changed in Stage 4b is that the precondition now has teeth: `requireSigningTerminal` asserts
 * `TransactionSigningGuard.assertMaySign` itself, and takes the TSE mode as a required parameter, so
 * a signing path physically cannot resolve a terminal without also checking that it may sign.
 * Before that, a deactivated or unregistered terminal could produce a signed Z-Bon while being
 * refused an ordinary sale.
 *
 * This set is therefore NOT the complete list of every route reaching a TSE sign call — it is the
 * complete list of routes whose signing is gated STATICALLY, by path. Do NOT add the
 * Z-Bon/Kassenbuch routes here: their gate is the row, not the route.
 *
 * One deliberate non-signing member: `POST /api/v1/terminals/session`. It mints the station JWT
 * the signing routes are later called with, so the ceiling is applied at the exchange — the
 * station learns it may not transact before it holds a credential, not on its first order.
 *
 * Update this set (and `TransactionalRoutesTest`) whenever a new per-terminal signing call site is
 * added.
 */
object TransactionalRoutes {
    private data class Pattern(
        val method: HttpMethod,
        val segments: List<String>,
    )

    private fun p(
        method: HttpMethod,
        route: String,
    ) = Pattern(method, route.trim('/').split('/'))

    private val patterns =
        listOf(
            p(HttpMethod.Post, "/api/orders"), // create order — BESTELLUNG
            p(HttpMethod.Post, "/api/items/cancel/{sourceTableId}"), // order-item cancel — Storno
            p(HttpMethod.Post, "/api/v1/bills"), // create bill — RECHNUNG
            p(HttpMethod.Get, "/api/bills/create-with-card"), // 2nd createBill caller — card-payment SSE flow
            p(HttpMethod.Delete, "/api/v1/bills/{id}"), // delete bill — reversal
            p(HttpMethod.Post, "/api/v1/bills/{id}/rebook"), // Zahlartwechsel — Storno + Neubuchung
            // Station-session exchange — does not sign, but MINTS the station JWT under which the
            // routes above are later called. Gating it here is what gives an unpaired /
            // deactivated / non-fiscal / unregistered station its 409 / 403 / 403 / 424 at the
            // exchange, before it ever holds a token (order-station shell, 2026-09-06).
            p(HttpMethod.Post, "/api/v1/terminals/session"),
            // EXCLUDED (non-signing): POST /api/v1/bills/{id}/print, POST /api/orders/reference-print
        )

    fun isTransactional(
        path: String,
        method: HttpMethod,
    ): Boolean {
        val segs = path.trim('/').split('/')
        return patterns.any { pat ->
            pat.method == method &&
                pat.segments.size == segs.size &&
                pat.segments.zip(segs).all { (p, s) -> p.startsWith("{") || p == s }
        }
    }
}
