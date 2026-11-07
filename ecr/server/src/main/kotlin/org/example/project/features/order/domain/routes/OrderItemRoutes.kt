/**
 * REST API routes for OrderItem operations.
 *
 * Throws PosException directly on validation/auth errors; the global StatusPages
 * plugin emits application/problem+json responses.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.order.domain.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.error.PosErrorCode
import org.example.project.features.order.domain.service.OrderItemService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getTokenClaims
import org.example.project.utils.InsufficientPermissionsException
import org.example.project.utils.describeDeferredApprovalResponses
import org.example.project.utils.requirePermissionOrDefer
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("OrderItemRoutes")

/**
 * Configures order item API routes.
 */
fun Route.orderItemRoutes() {
    val service by inject<OrderItemService>()

    authenticate("auth-jwt") {
        route("/api/items") {
            /**
             * List the order items of a table session.
             *
             * Description: The POS ticket view. Pass the `status` query parameter to narrow the result to one item status (for example OPEN or PAID); omitting it returns every item on the session.
             *
             * Tag: order-items
             * Path: sessionId Table session identifier. Must be a positive number.
             * Response: 400 sessionId is not a positive number.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/session/{sessionId}") {
                val sessionId = call.parameters["sessionId"]?.toLongOrNull()
                if (sessionId == null || sessionId <= 0L) {
                    throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.BadRequest,
                        "Invalid session identifier",
                    )
                }
                val itemStatus = call.request.queryParameters["status"]
                call.respond(HttpStatusCode.OK, service.getItemForTableSession(sessionId, itemStatus))
            }

            /**
             * List item cancellations in a date range.
             *
             * Description: Requires MANAGER role. Audit view over cancelled order items — who cancelled what, when and why. Both `startDate` and `endDate` query parameters are required.
             *
             * Tag: order-items
             * Response: 400 startDate or endDate is missing or blank.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get("/cancellations") {
                val startDate = call.request.queryParameters["startDate"]
                val endDate = call.request.queryParameters["endDate"]

                if (startDate.isNullOrBlank() || endDate.isNullOrBlank()) {
                    throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.BadRequest,
                        "startDate and endDate parameters are required",
                    )
                }

                call.respond(HttpStatusCode.OK, service.getCancellations(startDate, endDate))
            }

            /**
             * Get the transfer history of a table session.
             *
             * Description: Lists every item move into or out of this session, so a cashier can see where a guest's items went. Any authenticated employee may read it.
             *
             * Tag: order-items
             * Path: tableSessionId Table session identifier. Must be a positive number.
             * Response: 400 tableSessionId is not a positive number.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/transfers/{tableSessionId}") {
                val tableSessionId = call.parameters["tableSessionId"]?.toLongOrNull()
                if (tableSessionId == null || tableSessionId <= 0L) {
                    throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.BadRequest,
                        "Invalid table session identifier",
                    )
                }
                call.respond(HttpStatusCode.OK, service.getTransferHistory(tableSessionId))
            }

            /**
             * List item transfers in a date range.
             *
             * Description: Requires MANAGER role. Audit view over item moves between tables across the whole shop. Both `startDate` and `endDate` query parameters are required.
             *
             * Tag: order-items
             * Response: 400 startDate or endDate is missing or blank.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get("/transfers") {
                val startDate = call.request.queryParameters["startDate"]
                val endDate = call.request.queryParameters["endDate"]

                if (startDate.isNullOrBlank() || endDate.isNullOrBlank()) {
                    throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.BadRequest,
                        "startDate and endDate parameters are required",
                    )
                }

                call.respond(HttpStatusCode.OK, service.getTransfers(startDate, endDate))
            }

            /**
             * Cancel order items.
             *
             * Description: Hybrid authorization — a caller holding the cancel-orders permission (or covered by a temporary manager session on the device) acts immediately; anyone else has an approval request raised for a manager and gets 202 Accepted, with the cancellation executed only once approved. The cancellation is recorded on the TSE and in the audit trail.
             *
             * Tag: order-items
             * Path: sourceTableId Identifier of the table the items sit on. Must be a positive number.
             * Response: 202 An approval request was raised; a manager must approve before the items are cancelled.
             * Response: 400 sourceTableId is not a positive number, or the body failed validation (a reason is required — missing, blank or over 500 characters is refused).
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller may not cancel items and no manager approval could be requested.
             * Response: 404 The table, or one of the items, does not exist.
             * Response: 409 An item is no longer open — already paid or already cancelled.
             * Response: 503 TSE signing failed and the shop is configured to fail the operation.
             */
            post("/cancel/{sourceTableId}") {
                try {
                    val request = call.receive<OrderItemDto.CancelOrderItemsRequestDto>()
                    request.validate()
                    val sourceTableId = call.parameters["sourceTableId"]?.toLongOrNull()
                    if (sourceTableId == null || sourceTableId <= 0L) {
                        throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid source table identifier",
                        )
                    }

                    val tokenClaims = call.getTokenClaims() ?: return@post call.respond(HttpStatusCode.Unauthorized)

                    val authResult =
                        call.requirePermissionOrDefer(
                            requiredCapability = Capability.ORDER_ITEM_CANCEL,
                            operation = "CANCEL_ORDER_ITEMS",
                            details =
                                mapOf(
                                    "itemIds" to request.itemIds.joinToString(","),
                                    "itemCount" to request.itemIds.size.toString(),
                                    "reason" to (request.reason ?: ""),
                                ),
                        ) {
                            // Deferred action lambda — executed when manager approves
                            service.cancelOrderItems(
                                item = request.itemIds,
                                employeeId = tokenClaims.employeeId,
                                deviceId = tokenClaims.deviceId,
                                reason = request.reason,
                                sourceTableId = sourceTableId,
                            )
                            "Cancelled ${request.itemIds.size} items from table $sourceTableId"
                        }

                    if (authResult == null) return@post

                    logger.info(
                        "POST /api/items/cancel - Employee ${authResult.employeeNumber} " +
                            "cancelling ${request.itemIds.size} items via ${authResult.authorizationType}",
                    )

                    val result =
                        service.cancelOrderItems(
                            item = request.itemIds,
                            employeeId = authResult.employeeId,
                            deviceId = tokenClaims.deviceId,
                            reason = request.reason,
                            sourceTableId = sourceTableId,
                        )
                    logger.info(
                        "Successfully cancelled ${request.itemIds.size} order items for employee ${authResult.employeeNumber}",
                    )
                    call.respond(HttpStatusCode.OK, result.message)
                } catch (e: InsufficientPermissionsException) {
                    // Already responded in requirePermissionOrDefer
                }
            }.describeDeferredApprovalResponses()

            /**
             * Transfer order items to another table.
             *
             * Description: Hybrid authorization — a caller holding the transfer-items permission (or covered by a temporary manager session on the device) acts immediately; anyone else has an approval request raised for a manager and gets 202 Accepted, with the transfer executed only once approved. A free target table is opened for the transfer; for an order station that only happens through employee rotation.
             *
             * Tag: order-items
             * Path: currentTableId Identifier of the table the items sit on today. Must be a positive number.
             * Response: 202 An approval request was raised; a manager must approve before the items move.
             * Response: 400 currentTableId is not a positive number, or the body failed validation (a reason is required — missing, blank or over 500 characters is refused).
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller may not transfer items and no manager approval could be requested.
             * Response: 404 The source table, the target table, or one of the items does not exist.
             * Response: 409 An item is no longer open, the target session is closed, the caller's training mode differs from the source session's or from a free target table's rotated owner, or an order station would open a target table it may not open.
             */
            post("/transfer/{currentTableId}") {
                try {
                    val currentTableId = call.parameters["currentTableId"]?.toLongOrNull()
                    val request = call.receive<OrderItemDto.TransferOrderItemsRequestDto>()
                    request.validate()

                    if (currentTableId == null || currentTableId <= 0L) {
                        throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid current table identifier",
                        )
                    }

                    val tokenClaims = call.getTokenClaims() ?: return@post call.respond(HttpStatusCode.Unauthorized)

                    val authResult =
                        call.requirePermissionOrDefer(
                            requiredCapability = Capability.TABLE_SESSION_OVERRIDE,
                            operation = "TRANSFER_ORDER_ITEMS",
                            details =
                                mapOf(
                                    "itemIds" to request.itemIds.joinToString(","),
                                    "itemCount" to request.itemIds.size.toString(),
                                    "targetTableId" to request.targetTableId.toString(),
                                    "reason" to (request.reason ?: ""),
                                ),
                        ) {
                            service.transferOrderItems(
                                currentTableId = currentTableId,
                                item = request.itemIds,
                                employeeId = tokenClaims.employeeId,
                                deviceId = tokenClaims.deviceId,
                                targetTableId = request.targetTableId,
                                reason = request.reason,
                            )
                            "Transferred ${request.itemIds.size} items to table ${request.targetTableId}"
                        }

                    if (authResult == null) return@post

                    val result =
                        service.transferOrderItems(
                            currentTableId = currentTableId,
                            item = request.itemIds,
                            employeeId = authResult.employeeId,
                            deviceId = tokenClaims.deviceId,
                            targetTableId = request.targetTableId,
                            reason = request.reason,
                            // A manager lent to this device authorises the transfer; the requester decides who owns a table it opens.
                            ownerBasisEmployeeId = tokenClaims.employeeId,
                        )
                    logger.info(
                        "Successfully transferred ${request.itemIds.size} order items to table ${request.targetTableId}",
                    )
                    call.respond(HttpStatusCode.OK, result.message)
                } catch (e: InsufficientPermissionsException) {
                    // Already responded in requirePermissionOrDefer
                }
            }.describeDeferredApprovalResponses()
        }
    }
}
