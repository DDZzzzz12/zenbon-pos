package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Kassenbuch entries, cash books, Geldtransit and the Kassensturz close — `KassenbuchRoutes.kt`
 * and `CashBookRoutes.kt`.
 *
 * Entirely manager-gated today, so both capabilities sit in `MANAGER` — nothing here is till
 * work. Kept as a strict `_READ`/`_WRITE` pair rather than `_MANAGE` because a read-only
 * ACCOUNTANT reading the cash book without being able to post to it is a Stage 3 requirement.
 *
 * `CASHBOOK_ADMIN` currently backs no route here: it was the gate on the POS-ERP transit-outbox
 * abandon endpoint, which went with that integration. It is kept as the admin-level rung of this
 * module's ladder rather than deleted, so a future destructive cash-book action has a gate to use.
 */
object CashBookRules : RuleModule {
    override val name = "Kassenbuch"

    override val pathPrefixes = listOf("/api/v1/kassenbuch")

    override val allowedCapabilities =
        setOf(
            Capability.CASHBOOK_CLOSE,
            Capability.CASHBOOK_READ,
            Capability.CASHBOOK_WRITE,
            Capability.TERMINAL_MANAGE,
        )

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/v1/kassenbuch" to HttpMethod.Post) to Cap(Capability.CASHBOOK_WRITE),
            ("/api/v1/kassenbuch/balance" to HttpMethod.Get) to Cap(Capability.CASHBOOK_READ),
            ("/api/v1/kassenbuch/cash-count" to HttpMethod.Post) to Cap(Capability.CASHBOOK_WRITE),
            ("/api/v1/kassenbuch/cash-count/expected" to HttpMethod.Get) to Cap(Capability.CASHBOOK_READ),
            ("/api/v1/kassenbuch/available-cash" to HttpMethod.Get) to Cap(Capability.CASHBOOK_READ),
            ("/api/v1/kassenbuch/entries" to HttpMethod.Get) to Cap(Capability.CASHBOOK_READ),
            ("/api/v1/kassenbuch/entries/search" to HttpMethod.Get) to Cap(Capability.CASHBOOK_READ),
            ("/api/v1/kassenbuch/entries/recent" to HttpMethod.Get) to Cap(Capability.CASHBOOK_READ),
            ("/api/v1/kassenbuch/entries/{entryId}" to HttpMethod.Get) to Cap(Capability.CASHBOOK_READ),
            ("/api/v1/kassenbuch/entries/grouped/{headerId}" to HttpMethod.Get) to Cap(Capability.CASHBOOK_READ),
            ("/api/v1/kassenbuch/export/datev-xml" to HttpMethod.Get) to Cap(Capability.CASHBOOK_READ),
            ("/api/v1/kassenbuch/export/buchungsstapel" to HttpMethod.Get) to Cap(Capability.CASHBOOK_READ),
            ("/api/v1/kassenbuch/entries/{headerId}/link-document" to HttpMethod.Put) to Cap(Capability.CASHBOOK_WRITE),
            ("/api/v1/kassenbuch/entries/{headerId}/link-document" to HttpMethod.Delete) to Cap(Capability.CASHBOOK_WRITE),
            ("/api/v1/kassenbuch/entries/{headerId}/replace-document" to HttpMethod.Put) to Cap(Capability.CASHBOOK_WRITE),
            ("/api/v1/kassenbuch/entries/{headerId}/correct" to HttpMethod.Post) to Cap(Capability.CASHBOOK_WRITE),
            ("/api/v1/kassenbuch/export/datev-xml/statistics" to HttpMethod.Get) to Cap(Capability.CASHBOOK_READ),
            ("/api/v1/kassenbuch/transfers" to HttpMethod.Post) to Cap(Capability.CASHBOOK_WRITE),
            ("/api/v1/kassenbuch/transfers/{transferId}/reverse" to HttpMethod.Post) to Cap(Capability.CASHBOOK_WRITE),
            ("/api/v1/kassenbuch/books" to HttpMethod.Get) to Cap(Capability.CASHBOOK_READ),
            ("/api/v1/kassenbuch/books" to HttpMethod.Post) to Cap(Capability.CASHBOOK_WRITE),
            ("/api/v1/kassenbuch/books/{bookId}" to HttpMethod.Put) to Cap(Capability.CASHBOOK_WRITE),
            ("/api/v1/kassenbuch/books/{bookId}/close/preview" to HttpMethod.Get) to Cap(Capability.CASHBOOK_READ),
            ("/api/v1/kassenbuch/books/{bookId}/close" to HttpMethod.Post) to Cap(Capability.CASHBOOK_CLOSE),
            ("/api/v1/kassenbuch/books/{bookId}/closes" to HttpMethod.Get) to Cap(Capability.CASHBOOK_READ),
            ("/api/v1/kassenbuch/books/{bookId}/signing-terminal/candidates" to HttpMethod.Get) to Cap(Capability.TERMINAL_MANAGE),
            ("/api/v1/kassenbuch/books/{bookId}/signing-terminal" to HttpMethod.Put) to Cap(Capability.TERMINAL_MANAGE),
        )
}
