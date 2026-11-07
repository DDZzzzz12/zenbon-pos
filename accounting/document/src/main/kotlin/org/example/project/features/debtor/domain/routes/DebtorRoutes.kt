/**
 * Debtor API routes — the customers we invoice.
 *
 * CRITICAL AUTHENTICATION PATTERN:
 * - Single authenticate("auth-jwt") wrapper for the entire route group
 * - Prevents "Default authentication configuration was not found" runtime error
 * - Never nest multiple authenticate blocks
 *
 * Endpoints:
 * - GET    /api/debtors      - List debtors, optionally only active ones
 * - GET    /api/debtors/{id} - One debtor
 * - POST   /api/debtors      - Create a debtor
 * - PUT    /api/debtors/{id} - Replace the editable fields
 * - DELETE /api/debtors/{id} - Remove a debtor no invoice references
 */
package org.example.project.features.debtor.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.example.project.core.domain.dto.debtor.DebtorDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.debtor.domain.service.DebtorService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject

fun Route.debtorRoutes() {
    val debtorService by inject<DebtorService>()

    // CRITICAL: Single authenticate wrapper for all endpoints
    authenticate("auth-jwt") {
        route("/api/debtors") {
            /**
             * List debtors.
             *
             * Description: Debtors ordered by name. Set activeOnly to hide deactivated ones. The search parameter matches name, city, VAT ID or debtor number case-insensitively; a blank search is ignored rather than returning nothing. Each row carries how many invoices reference it, which is what decides whether it may be deleted.
             *
             * Tag: debtors
             * Response: 200 The debtor list with a total count.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get {
                val activeOnly = call.request.queryParameters["activeOnly"]?.toBooleanStrictOrNull() ?: false
                val search = call.request.queryParameters["search"]
                val limit =
                    call.request.queryParameters["limit"]
                        ?.toIntOrNull()
                        ?.coerceIn(1, 500) ?: 200
                val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L
                call.respond(HttpStatusCode.OK, debtorService.list(activeOnly, search, limit, offset))
            }

            /**
             * Fetch one debtor.
             *
             * Description: Requires MANAGER role.
             *
             * Tag: debtors
             * Path: id Debtor identifier.
             * Response: 200 The debtor.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No debtor with that id.
             */
            get("/{id}") {
                call.respond(HttpStatusCode.OK, debtorService.get(call.longParam("id")))
            }

            /**
             * Create a debtor.
             *
             * Description: Requires MANAGER role. The debtor number is the Steuerberater's Personenkonto and is never generated — leave it empty until one is assigned.
             *
             * Tag: debtors
             * Response: 201 The created debtor.
             * Response: 400 The body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 409 DEBTOR_NUMBER_TAKEN or DEBTOR_VAT_ID_TAKEN — another debtor already carries it.
             */
            post {
                val dto = debtorService.create(call.receive<DebtorDto.CreateRequestDto>())
                call.respond(HttpStatusCode.Created, dto)
            }

            /**
             * Replace a debtor's editable fields.
             *
             * Description: Requires MANAGER role. A full replace, not a patch — an omitted optional field is written as null. Setting isActive to false deactivates the debtor, which is the correct way to retire one that has been invoiced.
             *
             * Tag: debtors
             * Path: id Debtor identifier.
             * Response: 200 The updated debtor.
             * Response: 400 id is not a number, or the body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No debtor with that id.
             * Response: 409 DEBTOR_NUMBER_TAKEN or DEBTOR_VAT_ID_TAKEN — another debtor already carries it.
             */
            put("/{id}") {
                val dto =
                    debtorService.update(
                        call.longParam("id"),
                        call.receive<DebtorDto.UpdateRequestDto>(),
                    )
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Delete a debtor.
             *
             * Description: Requires MANAGER role. Only for a debtor no invoice references. One that has been invoiced is kept — those invoices are retained ten years under §147 AO and must keep pointing at the party they were issued to — and is deactivated instead.
             *
             * Tag: debtors
             * Path: id Debtor identifier.
             * Response: 204 The debtor was removed.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No debtor with that id.
             * Response: 409 DEBTOR_IN_USE — an invoice references it; deactivate it instead.
             */
            delete("/{id}") {
                debtorService.delete(call.longParam("id"))
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun ApplicationCall.longParam(name: String): Long =
    parameters[name]?.toLongOrNull()
        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid $name")
