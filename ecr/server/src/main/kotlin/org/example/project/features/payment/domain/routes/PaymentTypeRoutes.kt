/**
 * HTTP routes for PaymentType API endpoints.
 *
 * Throws PosException directly on validation/auth errors; the global StatusPages
 * plugin emits application/problem+json responses. Service throws PosException
 * directly (Stage B Batch 1) — no Result matching here.
 */
package org.example.project.features.payment.domain.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.core.domain.dto.payment.PaymentTypeDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.payment.domain.service.PaymentTypeService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getEmployeeIdFromJwt
import org.koin.ktor.ext.inject

/**
 * Configures the routing for payment type-related API endpoints.
 */
fun Routing.paymentTypeRoutes() {
    val paymentTypeService by inject<PaymentTypeService>()

    authenticate("auth-jwt") {
        route("/api/payment-types") {
            /**
             * Get all payment types.
             *
             * Description: Returns every configured payment type, newest first. Pass `includeInactive=true` to include deactivated ones (default false). Any authenticated employee may read this — the POS needs it to render the payment screen.
             *
             * Tag: payment-types
             * Response: 401 Missing or invalid bearer token.
             */
            get {
                val includeInactive = call.request.queryParameters["includeInactive"]?.toBoolean() ?: false
                call.respond(HttpStatusCode.OK, paymentTypeService.getAllPaymentTypes(includeInactive))
            }

            /**
             * Create a payment type.
             *
             * Description: Requires MANAGER role. The acting employee is taken from the JWT and recorded on the audit trail.
             *
             * Tag: payment-types
             * Response: 400 The request body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 409 A payment type with that name already exists.
             */
            post {
                val userId = call.getEmployeeIdFromJwt()
                val request = call.receive<PaymentTypeDto.CreatePaymentTypeRequestDto>()
                val response = paymentTypeService.createPaymentType(userId ?: "", request)
                call.respond(HttpStatusCode.Created, response)
            }

            /**
             * Get active payment types.
             *
             * Returns only active payment types, as lightweight summaries for pickers.
             * Requires MANAGER role.
             *
             * Tag: payment-types
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get("/active") {
                call.respond(HttpStatusCode.OK, paymentTypeService.getActivePaymentTypes())
            }

            /**
             * Get payment type usage statistics.
             *
             * Description: Aggregates how often each payment type has been used. Requires MANAGER role.
             *
             * Tag: payment-types
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get("/statistics") {
                call.respond(HttpStatusCode.OK, paymentTypeService.getPaymentTypeStatistics())
            }

            /**
             * Get payment types changed since a timestamp.
             *
             * Description: Incremental sync feed for offline-capable clients — returns only the payment types updated after the given timestamp.
             *
             * Tag: payment-types
             * Path: lastSyncTime ISO-8601 timestamp of the client's last successful sync.
             * Response: 400 lastSyncTime is missing or blank.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/sync/{lastSyncTime}") {
                val lastSyncTime = call.parameters["lastSyncTime"]
                if (lastSyncTime.isNullOrBlank()) {
                    throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.BadRequest,
                        "lastSyncTime is required",
                    )
                }
                call.respond(HttpStatusCode.OK, paymentTypeService.getPaymentTypesUpdatedAfter(lastSyncTime))
            }

            /**
             * Get a payment type by ID.
             *
             * Tag: payment-types
             * Path: id Payment type identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No payment type with that id.
             */
            get("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "id must be a number",
                        )
                call.respond(HttpStatusCode.OK, paymentTypeService.getPaymentTypeById(id))
            }

            /**
             * Update a payment type.
             *
             * Description: Requires MANAGER role. Only the fields present in the body are changed.
             *
             * Tag: payment-types
             * Path: id Payment type identifier.
             * Response: 400 id is not a number, or the body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No payment type with that id.
             */
            put("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "id must be a number",
                        )
                val userId = call.getEmployeeIdFromJwt()
                val request = call.receive<PaymentTypeDto.UpdatePaymentTypeRequestDto>()
                val response = paymentTypeService.updatePaymentType(userId ?: "", id, request)
                call.respond(HttpStatusCode.OK, response)
            }

            /**
             * Delete a payment type.
             *
             * Description: Requires MANAGER role. Payment types referenced by existing bills are deactivated rather than removed, so historical documents stay resolvable.
             *
             * Tag: payment-types
             * Path: id Payment type identifier.
             * Response: 204 Deleted. No body.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No payment type with that id.
             */
            delete("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "id must be a number",
                        )
                val userId = call.getEmployeeIdFromJwt()
                paymentTypeService.deletePaymentType(userId ?: "", id)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
