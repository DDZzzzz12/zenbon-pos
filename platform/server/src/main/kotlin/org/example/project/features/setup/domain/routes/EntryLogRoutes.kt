/**
 * REST API routes for EntryLog operations.
 *
 * Defines HTTP endpoints for querying operation logs. Routes throw [PosException] on
 * parameter-validation failures; service errors propagate via StatusPages.
 *
 * Note: the legacy GET /api/entry-logs/{lastSyncTime}/{operation} timestamp-based
 * deletion-sync route was retired in Phase 9 of the change-feed redesign (2026-06-23).
 * The authoritative feed is now GET /api/changes?since={seq}.
 */
package org.example.project.features.setup.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.example.project.core.domain.dto.setup.EntryLogDto
import org.example.project.features.audit.domain.service.EntryLogService
import org.koin.ktor.ext.inject

/**
 * Configures entry log API routes.
 */
fun Route.entryLogRoutes() {
    val service by inject<EntryLogService>()

    authenticate("auth-jwt") {
        route("/api/entry-logs") {
            /**
             * List entry logs, paginated.
             *
             * Description: Requires MANAGER role. The operation log of who changed what. Optional query parameters filter it — `type`, `operation`, `entityName`, `entityId`, `employeeNumber`, `fromDate`, `toDate` and a free-text `search`. Paging is by `page` (from 0) and `size` (1–100, default 20), or by passing the previous response's `nextCursor` as `cursor`, which is keyset-based and ignores `page`.
             *
             * Tag: entry-logs
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get("/paginated") {
                val query =
                    EntryLogDto.EntryLogQueryDto(
                        type = call.request.queryParameters["type"],
                        operation = call.request.queryParameters["operation"],
                        entityName = call.request.queryParameters["entityName"],
                        entityId = call.request.queryParameters["entityId"],
                        employeeNumber = call.request.queryParameters["employeeNumber"],
                        fromDate = call.request.queryParameters["fromDate"],
                        toDate = call.request.queryParameters["toDate"],
                        search = call.request.queryParameters["search"],
                        page = (call.request.queryParameters["page"]?.toIntOrNull() ?: 0).coerceAtLeast(0),
                        pageSize = (call.request.queryParameters["size"]?.toIntOrNull() ?: 20).coerceIn(1, 100),
                        // Keyset cursor from a previous response's nextCursor; when present
                        // the service ignores `page` and pages by (created_at, id) keyset.
                        cursor = call.request.queryParameters["cursor"],
                    )

                val response = service.getPaginatedLogs(query)
                call.respond(HttpStatusCode.OK, response)
            }
        }
    }
}
