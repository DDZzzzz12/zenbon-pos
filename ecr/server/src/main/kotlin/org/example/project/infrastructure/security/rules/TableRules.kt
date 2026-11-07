package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Tables, table sessions, room elements and location types — `RTableRoutes.kt`,
 * `RoomElementRoutes.kt`, `LocationTypeRoutes.kt`.
 *
 * Three access shapes live here and each gets its own capability:
 *  - the till reads the floor plan and runs sessions — `TABLE_READ`, `TABLE_WRITE`;
 *  - editing the floor plan is shop configuration — `CONFIG_WRITE`;
 *  - a manager overriding a live session (force-close, transfer) — `TABLE_SESSION_OVERRIDE`,
 *    which cannot be `TABLE_WRITE` because that sits at terminal level.
 *
 * `repair-completed-sessions` is `Role(ADMIN)` and carries `TABLE_REPAIR`.
 */
object TableRules : RuleModule {
    override val name = "Tables and floor plan"

    override val pathPrefixes = listOf("/api/tables", "/api/room-elements", "/api/location-types")

    override val allowedCapabilities =
        setOf(Capability.CONFIG_WRITE, Capability.TABLE_READ, Capability.TABLE_REPAIR, Capability.TABLE_SESSION_OVERRIDE, Capability.TABLE_WRITE)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/tables/{id}/currentSession" to HttpMethod.Get) to Cap(Capability.TABLE_READ),
            ("/api/tables/search" to HttpMethod.Get) to Cap(Capability.TABLE_READ),
            ("/api/tables/rotation/next" to HttpMethod.Get) to Cap(Capability.TABLE_READ),
            ("/api/tables" to HttpMethod.Get) to Cap(Capability.TABLE_READ),
            ("/api/tables" to HttpMethod.Post) to Cap(Capability.CONFIG_WRITE),
            ("/api/tables/{id}" to HttpMethod.Get) to Cap(Capability.TABLE_READ),
            ("/api/tables/{id}" to HttpMethod.Put) to Cap(Capability.CONFIG_WRITE),
            ("/api/tables/batch-update-positions" to HttpMethod.Post) to Cap(Capability.CONFIG_WRITE),
            ("/api/tables/{id}" to HttpMethod.Delete) to Cap(Capability.CONFIG_WRITE),
            ("/api/tables/{id}/transfer" to HttpMethod.Post) to Cap(Capability.TABLE_SESSION_OVERRIDE),
            ("/api/tables/{id}/session" to HttpMethod.Delete) to Cap(Capability.TABLE_SESSION_OVERRIDE),
            ("/api/tables/sessions/{sessionId}/customer" to HttpMethod.Get) to Cap(Capability.TABLE_READ),
            ("/api/tables/sessions/{sessionId}/assign-customer" to HttpMethod.Post) to Cap(Capability.TABLE_WRITE),
            ("/api/tables/sessions/{sessionId}/remove-customer" to HttpMethod.Delete) to Cap(Capability.TABLE_WRITE),
            ("/api/tables/sessions" to HttpMethod.Get) to Cap(Capability.TABLE_READ),
            ("/api/tables/sessions/{id}" to HttpMethod.Get) to Cap(Capability.TABLE_READ),
            ("/api/tables/import-csv/{overwrite}" to HttpMethod.Post) to Cap(Capability.CONFIG_WRITE),
            ("/api/tables/{id}/request-billing" to HttpMethod.Post) to Cap(Capability.TABLE_WRITE),
            ("/api/tables/billing-requests/pending" to HttpMethod.Get) to Cap(Capability.TABLE_READ),
            ("/api/tables/sync/{lastSyncTime}" to HttpMethod.Get) to Cap(Capability.TABLE_READ),
            ("/api/tables/repair-completed-sessions" to HttpMethod.Post) to Cap(Capability.TABLE_REPAIR),
            ("/api/room-elements" to HttpMethod.Get) to Cap(Capability.TABLE_READ),
            ("/api/room-elements" to HttpMethod.Post) to Cap(Capability.CONFIG_WRITE),
            ("/api/room-elements/{id}" to HttpMethod.Get) to Cap(Capability.TABLE_READ),
            ("/api/room-elements/{id}" to HttpMethod.Put) to Cap(Capability.CONFIG_WRITE),
            ("/api/room-elements/{id}" to HttpMethod.Delete) to Cap(Capability.CONFIG_WRITE),
            ("/api/room-elements/{id}/position" to HttpMethod.Patch) to Cap(Capability.CONFIG_WRITE),
            ("/api/room-elements/{id}/dimensions" to HttpMethod.Patch) to Cap(Capability.CONFIG_WRITE),
            ("/api/room-elements/by-type/{type}" to HttpMethod.Get) to Cap(Capability.TABLE_READ),
            ("/api/room-elements/by-location/{locationTypeId}" to HttpMethod.Get) to Cap(Capability.TABLE_READ),
            ("/api/room-elements/sync/{lastSyncTime}" to HttpMethod.Get) to Cap(Capability.TABLE_READ),
            ("/api/tables/combinable" to HttpMethod.Get) to Cap(Capability.TABLE_READ),
            ("/api/tables/combinable" to HttpMethod.Put) to Cap(Capability.CONFIG_WRITE),
            ("/api/tables/{id}/combinable" to HttpMethod.Get) to Cap(Capability.TABLE_READ),
            ("/api/tables/{id}/combinable" to HttpMethod.Put) to Cap(Capability.CONFIG_WRITE),
            ("/api/location-types" to HttpMethod.Get) to Cap(Capability.TABLE_READ),
            ("/api/location-types/{id}" to HttpMethod.Get) to Cap(Capability.TABLE_READ),
            ("/api/location-types" to HttpMethod.Post) to Cap(Capability.CONFIG_WRITE),
            ("/api/location-types/{id}" to HttpMethod.Put) to Cap(Capability.CONFIG_WRITE),
            ("/api/location-types/{id}" to HttpMethod.Delete) to Cap(Capability.CONFIG_WRITE),
        )
}
