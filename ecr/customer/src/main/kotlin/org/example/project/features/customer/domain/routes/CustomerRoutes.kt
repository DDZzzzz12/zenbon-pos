/**
 * REST API routes for Customer operations.
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
import org.example.project.core.domain.dto.customer.CustomerDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.customer.domain.service.CustomerService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject
import java.util.UUID

/**
 * Configure Customer routes.
 *
 * All routes are protected by JWT authentication and organized under /api/customers.
 */
fun Route.customerRoutes() {
    val customerService by inject<CustomerService>()

    authenticate("auth-jwt") {
        route("/api/customers") {
            /**
             * Search customers.
             *
             * Description: Type-ahead lookup for the till, matching name, phone or email. The term comes from the required `q` query parameter and `limit` is clamped to 1–20 (default 10).
             *
             * Tag: customers
             * Response: 400 The q query parameter is missing or blank.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/search") {
                val query = call.request.queryParameters["q"]
                if (query.isNullOrBlank()) {
                    throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.BadRequest,
                        "Query parameter 'q' is required",
                    )
                }
                val limit =
                    call.request.queryParameters["limit"]
                        ?.toIntOrNull()
                        ?.coerceIn(1, 20) ?: 10
                call.respond(HttpStatusCode.OK, customerService.searchCustomers(query, limit))
            }

            /**
             * Get a customer by ID.
             *
             * Tag: customers
             * Path: id Customer identifier, a UUID.
             * Response: 400 id is not a valid UUID.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No customer with that id.
             */
            get("/{id}") {
                val customerId = parseCustomerId(call.parameters["id"])
                call.respond(HttpStatusCode.OK, customerService.getCustomerById(customerId))
            }

            /**
             * List customers.
             *
             * Description: Pages with `limit` (default 50) and `offset` (default 0). `active=true|false` narrows by status and `search` applies a free-text filter.
             *
             * Tag: customers
             * Response: 401 Missing or invalid bearer token.
             */
            get {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                val active = call.request.queryParameters["active"]?.toBooleanStrictOrNull()
                val searchQuery = call.request.queryParameters["search"]
                call.respond(
                    HttpStatusCode.OK,
                    customerService.getAllCustomers(limit, offset, active, searchQuery),
                )
            }

            /**
             * Create a customer.
             *
             * Description: Open to any authenticated employee — a cashier registers a walk-in for a delivery or an account sale. Addresses are added separately.
             *
             * Tag: customers
             * Response: 400 The request body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 409 A customer with that identity already exists.
             */
            post {
                val request = call.receive<CustomerDto.CreateCustomerRequestDto>()
                call.respond(HttpStatusCode.Created, customerService.createCustomer(request))
            }
        }
    }
}

private fun parseCustomerId(raw: String?): UUID =
    try {
        UUID.fromString(raw)
    } catch (e: IllegalArgumentException) {
        throw PosException(
            PosErrorCode.INVALID_REQUEST,
            HttpStatusCode.BadRequest,
            "Invalid customer ID format",
        )
    }
