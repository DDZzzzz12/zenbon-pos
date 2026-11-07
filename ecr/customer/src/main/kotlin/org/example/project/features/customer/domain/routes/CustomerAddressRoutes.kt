/**
 * REST API routes for CustomerAddress operations.
 *
 * Throws PosException directly on validation/auth errors; the global StatusPages
 * plugin emits application/problem+json responses. Service throws PosException
 * directly (Stage B Batch 1) — no Result matching here.
 */
package org.example.project.features.customer.domain.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.core.domain.dto.customer.CustomerAddressDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.customer.domain.service.CustomerAddressService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject
import java.util.UUID

/**
 * Configure CustomerAddress routes.
 *
 * All routes are protected by JWT authentication and organized under /api/customer-addresses.
 */
fun Route.customerAddressRoutes() {
    val customerAddressService by inject<CustomerAddressService>()

    authenticate("auth-jwt") {
        route("/api/customer-addresses") {
            /**
             * List customer addresses.
             *
             * Description: Pages with `limit` (default 50) and `offset` (default 0).
             *
             * Tag: customer-addresses
             * Response: 401 Missing or invalid bearer token.
             */
            get {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                call.respond(HttpStatusCode.OK, customerAddressService.getAllAddresses(limit, offset))
            }

            /**
             * List one customer's addresses.
             *
             * Description: What the delivery flow calls once a customer is chosen. The `type` query parameter narrows to one address kind, for example billing or delivery.
             *
             * Tag: customer-addresses
             * Path: customerId Customer identifier, a UUID.
             * Response: 400 customerId is missing, or is not a valid UUID.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/customer/{customerId}") {
                val customerUuid = parseUuid(call.parameters["customerId"], "customer ID")
                val addressType = call.request.queryParameters["type"]
                call.respond(
                    HttpStatusCode.OK,
                    customerAddressService.getAddressesByCustomerId(customerUuid, addressType),
                )
            }

            /**
             * Create a customer address.
             *
             * Description: Adds an address to an existing customer. A delivery order needs one before it can be booked.
             *
             * Tag: customer-addresses
             * Response: 400 The request body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No customer with that id.
             */
            post {
                val request = call.receive<CustomerAddressDto.CreateCustomerAddressRequestDto>()
                call.respond(HttpStatusCode.Created, customerAddressService.createAddress(request))
            }
        }
    }
}

private fun parseUuid(
    raw: String?,
    label: String,
): UUID {
    if (raw.isNullOrBlank()) {
        throw PosException(
            PosErrorCode.INVALID_REQUEST,
            HttpStatusCode.BadRequest,
            "$label is required",
        )
    }
    return try {
        UUID.fromString(raw)
    } catch (e: IllegalArgumentException) {
        throw PosException(
            PosErrorCode.INVALID_REQUEST,
            HttpStatusCode.BadRequest,
            "Invalid $label format",
        )
    }
}
