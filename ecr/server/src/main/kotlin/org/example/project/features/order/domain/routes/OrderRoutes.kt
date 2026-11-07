/**
 * REST API routes for Order operations.
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
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.security.heldCapabilities
import org.example.project.features.order.domain.service.OrderService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getTokenClaims
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("OrderRoutes")

/**
 * Configures order API routes.
 */
fun Route.orderRoutes() {
    val service by inject<OrderService>()

    // Login-less order station (X-Device-Token, no JWT) or a logged-in cashier: create order
    // only. A sibling `authenticate` block with a DIFFERENT provider list than the one below —
    // Ktor's FirstSuccessful strategy tries "auth-jwt" first and only falls through to
    // "device-principal" when no JWT principal is present, so the existing cashier flow is
    // unaffected. Kept as its OWN block (not merged into the "auth-jwt"-only block below) so the
    // device principal is accepted for order creation ONLY, never for the read/print routes.
    authenticate("auth-jwt", "device-principal") {
        /**
         * Create an order.
         *
         * Description: Books order items onto a table session. Accepts either a normal cashier JWT or a login-less order station's device token — the only route that takes the device principal. The acting employee and device are read from the token, never from the body. Answers 201 with the created order. When the order opens a free table through the round-robin rotation and `expectedNextEmployeeId` (the next employee the till showed) names someone other than the employee the rotation actually picked, the open still succeeds and the body carries `rotationShifted = true` and `rotationOwner`, the table's real owner; otherwise `rotationShifted` is false and `rotationOwner` is null.
         *
         * Tag: orders
         * Response: 401 Neither a valid bearer token nor a valid device token was supplied.
         * Response: 403 A line sets a custom price or a discount the product does not allow, or the order would be registered under the order station's shared account, under which no line is discounted or custom-priced at all.
         * Response: 404 The table, its open session, or one of the products does not exist.
         * Response: 409 The session is already closed, its training-mode flag differs from the caller's, a free table's rotated owner is in a different training mode than the caller, or an order station tried to open a free table it may not open (no rotation, empty pool, takeaway or virtual).
         * Response: 503 TSE signing failed and the shop is configured to fail the sale.
         */
        post("/api/orders") {
            val token =
                call.getTokenClaims()
                    ?: throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.Unauthorized,
                        "Invalid or missing authentication token",
                    )

            val request = call.receive<OrderDto.CreateOrderRequestDto>()
            // What the route gate resolved for THIS request — group, station mask and an active
            // step-up. The service must not re-resolve it: a second opinion is what could not see a
            // manager's lent DISCOUNT_GRANT / PRICE_OVERRIDE (R5-1).
            // Who the order is registered under gates pricing on its own: under the order station's
            // shared account no line is discounted or custom-priced, whatever it holds (E-2); a
            // person's own login prices as that person on any device.
            val response = service.createOrder(request, token.employeeId, token.deviceId, call.heldCapabilities())
            call.respond(HttpStatusCode.Created, response)
        }
    }

    // Everything else stays JWT-only.
    authenticate("auth-jwt") {
        route("/api/orders") {
            /**
             * List the orders of a table session.
             *
             * Description: Returns every order booked on the session, which the POS uses to rebuild the open table's ticket. Any authenticated employee may read it.
             *
             * Tag: orders
             * Path: tableSessionId Table session identifier. Must be a positive number.
             * Response: 400 tableSessionId is not a positive number.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/by-table-session/{tableSessionId}") {
                val tableSessionId = call.parameters["tableSessionId"]?.toLongOrNull()
                if (tableSessionId == null || tableSessionId <= 0) {
                    throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.BadRequest,
                        "Invalid table session ID format. Must be a positive number.",
                    )
                }
                call.respond(HttpStatusCode.OK, service.getOrdersByTableSessionId(tableSessionId))
            }

            /**
             * Print a reference receipt.
             *
             * Description: Prints the guest's current items as a non-fiscal reference slip — it is not a bill, creates no TSE transaction and settles nothing. The printing employee is taken from the JWT.
             *
             * Tag: orders
             * Response: 400 The selection contains no order items.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 The employee, the table, or one of the order items does not exist.
             * Response: 409 Printing is disabled for this shop (`PRINTING_DISABLED`); no print job is created.
             * Response: 502 The printer reported a failed print job (`PRINTER_UNAVAILABLE`); the message carries the printer's reason.
             */
            post("/reference-print") {
                val token =
                    call.getTokenClaims()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.Unauthorized,
                            "Invalid or missing authentication token",
                        )

                val request = call.receive<OrderItemDto.ReferencePrintRequestDto>()
                service.referencePrint(request.tableId, request.orderItemIds, token.employeeId)
                call.respond(HttpStatusCode.OK)
            }
        }
    }
}
