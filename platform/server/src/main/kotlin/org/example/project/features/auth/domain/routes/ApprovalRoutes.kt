/**
 * API routes for approval request management.
 *
 * Provides endpoints for creating, viewing, approving, and denying approval
 * requests in the hybrid authorization system. Cashiers create requests which
 * are sent via WebSocket to managers for real-time approval.
 *
 * All routes require JWT authentication.
 *
 * Migrated to throw-based error handling (Stage B Batch 7). Service throws
 * PosException; the global StatusPages plugin converts to application/problem+json.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.auth.domain.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.core.error.PosErrorCode
import org.example.project.features.auth.domain.service.ApprovalRequestService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getTokenClaims
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

/**
 * Configure approval request routes.
 *
 * Endpoints:
 * - GET /api/auth/approvals/pending - Get all pending approval requests (managers only)
 * - POST /api/auth/approvals/{requestId}/approve - Approve a request (managers only)
 * - POST /api/auth/approvals/{requestId}/deny - Deny a request (managers only)
 * - GET /api/auth/approvals/{requestId}/status - Get status of specific request
 * - DELETE /api/auth/approvals/{requestId} - Cancel a pending request
 */
fun Route.approvalRoutes() {
    val service by inject<ApprovalRequestService>()
    val logger = LoggerFactory.getLogger("ApprovalRoutes")

    authenticate("auth-jwt") {
        route("/api/auth/approvals") {
            /**
             * List pending approval requests.
             *
             * Description: Requires MANAGER role. Returns every approval request a cashier has raised that is still awaiting a decision — the queue a manager works through on the approval screen.
             *
             * Tag: approvals
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get("/pending") {
                call.getTokenClaims()
                    ?: throw PosException(
                        PosErrorCode.AUTH_INVALID_CREDENTIALS,
                        HttpStatusCode.Unauthorized,
                        "Authentication required",
                    )

                val pending = service.getPendingRequests()
                logger.debug("Found {} pending requests", pending.size)
                call.respond(HttpStatusCode.OK, pending)
            }

            /**
             * Approve a pending request.
             *
             * Description: Requires MANAGER role. The approving manager is taken from the JWT, not the body, and is recorded on the request. The waiting cashier is notified over the live stream.
             *
             * Tag: approvals
             * Path: requestId Identifier of the approval request.
             * Response: 400 requestId is missing.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No approval request with that id.
             * Response: 409 The request is no longer pending — already decided, cancelled or expired.
             */
            post("/{requestId}/approve") {
                val requestId =
                    call.parameters["requestId"]
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Missing requestId parameter",
                        )

                val claims =
                    call.getTokenClaims()
                        ?: throw PosException(
                            PosErrorCode.AUTH_INVALID_CREDENTIALS,
                            HttpStatusCode.Unauthorized,
                            "Authentication required",
                        )

                val response =
                    service.approveRequest(
                        requestId = requestId,
                        managerId = claims.employeeId,
                    )
                logger.info("Request approved: {} by {}", requestId, claims.employeeId)
                call.respond(HttpStatusCode.OK, response)
            }

            /**
             * Deny a pending request.
             *
             * Description: Requires MANAGER role. The body must carry a `reason`, which is stored with the decision and shown to the cashier. The denying manager is taken from the JWT.
             *
             * Tag: approvals
             * Path: requestId Identifier of the approval request.
             * Response: 400 requestId is missing, or the body carries no reason.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No approval request with that id.
             * Response: 409 The request is no longer pending — already decided, cancelled or expired.
             */
            post("/{requestId}/deny") {
                val requestId =
                    call.parameters["requestId"]
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Missing requestId parameter",
                        )

                val claims =
                    call.getTokenClaims()
                        ?: throw PosException(
                            PosErrorCode.AUTH_INVALID_CREDENTIALS,
                            HttpStatusCode.Unauthorized,
                            "Authentication required",
                        )

                // Get denial reason from request body
                val requestBody = call.receive<Map<String, String>>()
                val reason =
                    requestBody["reason"]
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Denial reason is required",
                        )

                logger.info("{} denying request: {} - Reason: {}", claims.employeeId, requestId, reason)

                val response =
                    service.denyRequest(
                        requestId = requestId,
                        managerId = claims.employeeId,
                        reason = reason,
                    )
                logger.info("Request denied: {} by {}", requestId, claims.employeeId)
                call.respond(HttpStatusCode.OK, response)
            }

            /**
             * Get the status of an approval request.
             *
             * Description: Any authenticated employee may poll this — it is how the requesting cashier's device learns the outcome if the live stream dropped. Once decided, the payload also carries the manager's response and, for a denial, the reason.
             *
             * Tag: approvals
             * Path: requestId Identifier of the approval request.
             * Response: 400 requestId is missing.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No approval request with that id.
             */
            get("/{requestId}/status") {
                val requestId =
                    call.parameters["requestId"]
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Missing requestId parameter",
                        )

                logger.debug("Checking status of request: {}", requestId)
                val status = service.getRequestStatus(requestId)
                logger.debug("Request status: {}", status.status)
                call.respond(HttpStatusCode.OK, status)
            }

            /**
             * Cancel a pending request.
             *
             * Description: Requires MANAGER role. Withdraws a request that is still pending, so no manager can act on it afterwards. A request that has already been approved, denied, cancelled or expired cannot be cancelled.
             *
             * Tag: approvals
             * Path: requestId Identifier of the approval request.
             * Response: 400 requestId is missing.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No approval request with that id.
             * Response: 409 The request is no longer pending.
             */
            delete("/{requestId}") {
                val requestId =
                    call.parameters["requestId"]
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Missing requestId parameter",
                        )

                logger.info("Cancelling request: {}", requestId)
                service.cancelRequest(requestId)
                logger.info("Request cancelled: {}", requestId)
                call.respond(HttpStatusCode.OK)
            }
        }
    }
}
