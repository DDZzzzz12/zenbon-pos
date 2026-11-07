/**
 * API routes for recoverable billing requests.
 *
 * Mirrors the approval-pending pattern: a billing request pushed over SSE to a
 * table owner is also parked server-side so a client can recover any missed
 * request via reconcile-on-connect.
 *
 * All routes require JWT authentication.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.table.domain.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.core.error.PosErrorCode
import org.example.project.core.websocket.PendingBillingRequestStore
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getTokenClaims
import org.slf4j.LoggerFactory

/**
 * Configure recoverable billing-request routes.
 *
 * Endpoints:
 * - GET /api/tables/billing-requests/pending - Pending billing requests for the caller (table owner)
 */
fun Route.billingRequestRoutes() {
    val logger = LoggerFactory.getLogger("BillingRequestRoutes")

    authenticate("auth-jwt") {
        route("/api/tables/billing-requests") {
            /**
             * List my pending billing requests.
             *
             * Description: Billing requests are pushed to the table owner over SSE and also parked server-side. A client calls this on connect to recover any request it missed while offline. The employee comes from the JWT, so every caller sees only their own.
             *
             * Tag: billing-requests
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             */
            get("/pending") {
                val claims =
                    call.getTokenClaims()
                        ?: throw PosException(
                            PosErrorCode.AUTH_INVALID_CREDENTIALS,
                            HttpStatusCode.Unauthorized,
                            "Authentication required",
                        )

                val pending = PendingBillingRequestStore.getForEmployee(claims.employeeId)
                logger.debug("Found {} pending billing requests for {}", pending.size, claims.employeeId)
                call.respond(HttpStatusCode.OK, pending)
            }
        }
    }
}
