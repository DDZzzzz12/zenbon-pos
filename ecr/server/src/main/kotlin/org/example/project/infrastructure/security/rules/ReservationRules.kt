package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Reservations as staff take and amend them — `ReservationRoutes.kt`.
 *
 * Taking, amending and assigning tables were all `Authenticated`, so `RESERVATION_READ` and
 * `RESERVATION_WRITE` sit in `TERMINAL_DEVICE`. The two destructive operations are manager-gated
 * and therefore carry `RESERVATION_DELETE` — one capability cannot hold both levels.
 *
 * Reservation *rules* and opening hours are configuration and live in `ReservationConfigRules`.
 */
object ReservationRules : RuleModule {
    override val name = "Reservations"

    override val pathPrefixes = listOf("/api/reservations")

    override val allowedCapabilities = setOf(Capability.RESERVATION_DELETE, Capability.RESERVATION_READ, Capability.RESERVATION_WRITE)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/reservations/search" to HttpMethod.Get) to Cap(Capability.RESERVATION_READ),
            ("/api/reservations/availability" to HttpMethod.Get) to Cap(Capability.RESERVATION_READ),
            ("/api/reservations" to HttpMethod.Get) to Cap(Capability.RESERVATION_READ),
            ("/api/reservations/{id}" to HttpMethod.Get) to Cap(Capability.RESERVATION_READ),
            ("/api/reservations" to HttpMethod.Post) to Cap(Capability.RESERVATION_WRITE),
            ("/api/reservations/{id}" to HttpMethod.Put) to Cap(Capability.RESERVATION_WRITE),
            ("/api/reservations/{id}/status" to HttpMethod.Put) to Cap(Capability.RESERVATION_WRITE),
            ("/api/reservations/{id}" to HttpMethod.Delete) to Cap(Capability.RESERVATION_DELETE),
            ("/api/reservations/bulk-auto-assign" to HttpMethod.Post) to Cap(Capability.RESERVATION_WRITE),
            ("/api/reservations/{id}/auto-assign" to HttpMethod.Post) to Cap(Capability.RESERVATION_WRITE),
            ("/api/reservations/{id}/assign-tables" to HttpMethod.Post) to Cap(Capability.RESERVATION_WRITE),
            ("/api/reservations/{id}/unassign-tables" to HttpMethod.Delete) to Cap(Capability.RESERVATION_DELETE),
            ("/api/reservations/{id}/tables" to HttpMethod.Get) to Cap(Capability.RESERVATION_READ),
        )
}
