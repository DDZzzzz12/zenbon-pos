package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Reservation rules and opening hours — `ReservationRuleRoutes.kt`, `OpeningHoursRoutes.kt`.
 *
 * `CONFIG_*` rather than `RESERVATION_*`: these configure how reservations behave rather than
 * being reservations, and they share the config access shape exactly — every client reads them,
 * only a manager edits them.
 */
object ReservationConfigRules : RuleModule {
    override val name = "Reservation configuration"

    override val pathPrefixes = listOf("/api/reservation-rules", "/api/opening-hours")

    override val allowedCapabilities = setOf(Capability.CONFIG_READ, Capability.CONFIG_WRITE)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/reservation-rules" to HttpMethod.Get) to Cap(Capability.CONFIG_READ),
            ("/api/reservation-rules/{id}" to HttpMethod.Get) to Cap(Capability.CONFIG_READ),
            ("/api/reservation-rules" to HttpMethod.Post) to Cap(Capability.CONFIG_WRITE),
            ("/api/reservation-rules/{id}" to HttpMethod.Put) to Cap(Capability.CONFIG_WRITE),
            ("/api/reservation-rules/{id}" to HttpMethod.Delete) to Cap(Capability.CONFIG_WRITE),
            ("/api/opening-hours" to HttpMethod.Get) to Cap(Capability.CONFIG_READ),
            ("/api/opening-hours" to HttpMethod.Put) to Cap(Capability.CONFIG_WRITE),
            ("/api/opening-hours/overrides" to HttpMethod.Get) to Cap(Capability.CONFIG_READ),
            ("/api/opening-hours/overrides" to HttpMethod.Post) to Cap(Capability.CONFIG_WRITE),
            ("/api/opening-hours/overrides/{id}" to HttpMethod.Put) to Cap(Capability.CONFIG_WRITE),
            ("/api/opening-hours/overrides/{id}" to HttpMethod.Delete) to Cap(Capability.CONFIG_WRITE),
        )
}
