package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Orders and order items — `OrderRoutes.kt`, `OrderItemRoutes.kt`.
 *
 * Creating an order was `Authenticated` and is reachable by a login-less order station, so
 * `ORDER_WRITE` sits in `TERMINAL_DEVICE`.
 *
 * The manager-gated cancellation list and the shop-wide transfer list carry `AUDIT_READ`: they
 * are records of what was reversed or moved. Note the *parameterised*
 * `/api/items/transfers/{tableSessionId}` is till work and keeps `ORDER_READ` — the two differ
 * only by a path segment, and by access level.
 *
 * Item cancel and transfer carry a plain `ORDER_WRITE` floor since Stage 1c — deliberately NOT
 * `CapWithOwnership`, because nothing about them is ownership. They are the approval-deferral shape:
 * a manager acts directly, anyone else gets a 202 and a pending request for a manager to approve.
 * The floor decides who may *attempt* it; `requirePermissionOrDefer` in the handler still decides
 * whether that attempt executes or waits.
 *
 * `ORDER_WRITE` rather than `ORDER_ITEM_CANCEL` on purpose: a cashier must keep reaching the defer
 * path, so a floor only a manager holds would break the approval flow outright. See
 * `NoOrphanCapabilityTest` for why `ORDER_ITEM_CANCEL` is still unattached.
 */
object OrderRules : RuleModule {
    override val name = "Orders"

    override val pathPrefixes = listOf("/api/orders", "/api/items")

    override val allowedCapabilities = setOf(Capability.AUDIT_READ, Capability.ORDER_READ, Capability.ORDER_WRITE)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/orders/by-table-session/{tableSessionId}" to HttpMethod.Get) to Cap(Capability.ORDER_READ),
            ("/api/orders" to HttpMethod.Post) to Cap(Capability.ORDER_WRITE),
            ("/api/orders/reference-print" to HttpMethod.Post) to Cap(Capability.ORDER_WRITE),
            ("/api/items/session/{sessionId}" to HttpMethod.Get) to Cap(Capability.ORDER_READ),
            ("/api/items/cancellations" to HttpMethod.Get) to Cap(Capability.AUDIT_READ),
            ("/api/items/cancel/{sourceTableId}" to HttpMethod.Post) to Cap(Capability.ORDER_WRITE),
            ("/api/items/transfer/{currentTableId}" to HttpMethod.Post) to Cap(Capability.ORDER_WRITE),
            ("/api/items/transfers/{tableSessionId}" to HttpMethod.Get) to Cap(Capability.ORDER_READ),
            ("/api/items/transfers" to HttpMethod.Get) to Cap(Capability.AUDIT_READ),
        )
}
