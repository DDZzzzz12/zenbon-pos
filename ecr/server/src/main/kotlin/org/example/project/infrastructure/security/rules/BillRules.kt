package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Bills — `BillRoutes.kt`, `CardPaymentSseRoutes.kt`.
 *
 * The only family where CASHIER is a distinct tier: reading bills was `Authenticated`, but
 * creating, printing and rebooking one (Zahlartwechsel) are `AnyRole(... CASHIER ...)`.
 *
 * Two fiscally significant operations get their own capabilities rather than folding into
 * `BILL_WRITE`. `BILL_VOID` (DELETE) is a TSE-signed reversal exported to DSFinV-K and is
 * manager-gated; `BILL_CHANGE_PAYMENT` gates `POST /{id}/rebook`, the Zahlartwechsel as Storno +
 * Neubuchung, which also signs to the TSE but is cashier work — a mis-keyed tender or a forgotten
 * tip is corrected at the register. Both were previously reachable only by role — the matching
 * `EXCEPTION_*` grants are dead in the registry, which is why `forGrant` maps them to null.
 *
 * `GET /api/bills/create-with-card` carries `BILL_WRITE` despite the verb: the card-payment SSE
 * flow streams progress over GET while actually creating a TSE-signed RECHNUNG.
 *
 * `POST /{billId}/print` carries `PRINTER_WRITE`, not `BILL_WRITE`. Handing a guest a paper copy of
 * a receipt is floor work, not a fiscal act: the handler reads the bill and hands it to the printer
 * — it signs nothing on the TSE (`TransactionalRoutes` lists it as excluded) and writes only a
 * `print_job_history` row, which records the action rather than the business. Under `BILL_WRITE` a
 * waiter — role `TERMINAL_DEVICE`, who may open the receipt because they hold `BILL_READ` — was
 * refused, and reprinting is an everyday request at the table (issue #1).
 *
 * `PRINTER_WRITE` is the capability that already means "may make this shop's printers print": it
 * gates the test print and `printer-discovery/print-pdf`. Every floor group holds it and the two
 * back-office groups, `AUDITOR` and `ACCOUNTANT`, do not — so the change widens the route to the
 * people working the till and to nobody in the office. It is the same reasoning `ZBonRules` applies
 * to `/api/z-bon/{id}/print`, which is likewise gated on operating the report rather than on
 * writing one.
 */
object BillRules : RuleModule {
    override val name = "Bills"

    override val pathPrefixes = listOf("/api/v1/bills", "/api/bills")

    override val allowedCapabilities =
        setOf(
            Capability.BILL_CHANGE_PAYMENT,
            Capability.BILL_READ,
            Capability.BILL_VOID,
            Capability.BILL_WRITE,
            Capability.PRINTER_WRITE,
        )

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/bills/create-with-card" to HttpMethod.Get) to Cap(Capability.BILL_WRITE),
            ("/api/v1/bills" to HttpMethod.Get) to Cap(Capability.BILL_READ),
            ("/api/v1/bills/{id}" to HttpMethod.Get) to Cap(Capability.BILL_READ),
            ("/api/v1/bills/number/{billNumber}" to HttpMethod.Get) to Cap(Capability.BILL_READ),
            ("/api/v1/bills/tse-transaction/{transactionNumber}" to HttpMethod.Get) to Cap(Capability.BILL_READ),
            ("/api/v1/bills/table-session/{tableSessionId}" to HttpMethod.Get) to Cap(Capability.BILL_READ),
            ("/api/v1/bills/search" to HttpMethod.Get) to Cap(Capability.BILL_READ),
            ("/api/v1/bills/summaries" to HttpMethod.Get) to Cap(Capability.BILL_READ),
            ("/api/v1/bills/statistics" to HttpMethod.Get) to Cap(Capability.BILL_READ),
            ("/api/v1/bills/{billId}/print" to HttpMethod.Post) to Cap(Capability.PRINTER_WRITE),
            ("/api/v1/bills" to HttpMethod.Post) to Cap(Capability.BILL_WRITE),
            ("/api/v1/bills/{id}" to HttpMethod.Delete) to Cap(Capability.BILL_VOID),
            ("/api/v1/bills/{id}/rebook" to HttpMethod.Post) to Cap(Capability.BILL_CHANGE_PAYMENT),
        )
}
