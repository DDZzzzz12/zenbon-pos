/**
 * Buchungsbeginn API Routes — the date open-item tracking starts from.
 *
 * CRITICAL AUTHENTICATION PATTERN:
 * - Single authenticate("auth-jwt") wrapper for entire route group
 * - Prevents "Default authentication configuration was not found" runtime error
 * - Never nest multiple authenticate blocks
 *
 * Endpoints:
 * - GET /api/finance/bookkeeping-start - Read it, writing the self-dated default on first call
 * - PUT /api/finance/bookkeeping-start - Replace it
 * - POST /api/finance/bookkeeping-start/acknowledge - Dismiss the banner for good
 */
package org.example.project.features.finance.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.example.project.core.domain.dto.finance.BookkeepingStartDto
import org.example.project.features.finance.domain.service.BookkeepingStartService
import org.koin.ktor.ext.inject

fun Route.bookkeepingStartRoutes() {
    val bookkeepingStartService by inject<BookkeepingStartService>()

    // CRITICAL: Single authenticate wrapper for all endpoints
    authenticate("auth-jwt") {
        route("/api/finance/bookkeeping-start") {
            /**
             * The Buchungsbeginn.
             *
             * Description: Requires MANAGER role. Returns the Buchungsbeginn, writing the first day of the current month if no setting exists yet. That write is deliberate: the alternative is a date constant baked into a migration, which is stale before the release ships. The call is idempotent - reading it repeatedly returns the same answer, including needsAcknowledgement, which stays true until a manager acknowledges the date or replaces it.
             *
             * Tag: finance
             * Response: 200 The Buchungsbeginn, with needsAcknowledgement true until it is confirmed.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No shop is configured yet.
             */
            get {
                call.respond(HttpStatusCode.OK, bookkeepingStartService.get())
            }

            /**
             * Acknowledge the Buchungsbeginn.
             *
             * Description: Requires MANAGER role. Records that a manager has seen the date the system chose, so the banner stops appearing. The acknowledgement is stored rather than inferred from a one-time response: an affordance tied to a single non-idempotent read is lost forever to a reload at the wrong moment, and the default is now created by whichever list request runs first, not only by opening the Zuordnung screen.
             *
             * Tag: finance
             * Response: 200 The Buchungsbeginn, with needsAcknowledgement false.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No shop is configured yet.
             */
            post("/acknowledge") {
                call.respond(HttpStatusCode.OK, bookkeepingStartService.acknowledge())
            }

            /**
             * Replace the Buchungsbeginn.
             *
             * Description: Requires MANAGER role. Moving the date re-filters both the Offen/Ueberfaellig buckets and the assignment queue, since documents dated before it stay visible and exportable but never count as open items or queue work. A date that is not ISO yyyy-MM-dd is refused rather than stored - a bad value here would empty both work lists, which reads exactly like everything being done.
             *
             * Tag: finance
             * Response: 200 The stored Buchungsbeginn.
             * Response: 400 startDate is not an ISO yyyy-MM-dd date.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No shop is configured yet.
             */
            put {
                val request = call.receive<BookkeepingStartDto.UpdateRequestDto>()
                call.respond(HttpStatusCode.OK, bookkeepingStartService.update(request.startDate))
            }
        }
    }
}
