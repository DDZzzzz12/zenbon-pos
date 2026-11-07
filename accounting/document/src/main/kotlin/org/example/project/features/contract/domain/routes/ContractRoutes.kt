/**
 * Contract API Routes — recurring obligations and their expected payments.
 *
 * CRITICAL AUTHENTICATION PATTERN:
 * - Single authenticate("auth-jwt") wrapper for entire route group
 * - Prevents "Default authentication configuration was not found" runtime error
 * - Never nest multiple authenticate blocks
 *
 * Endpoints:
 * - GET  /api/contracts                  - List contracts, optionally including deactivated ones
 * - GET  /api/contracts/alerts           - What every active contract is complaining about
 * - GET  /api/contracts/{id}             - One contract
 * - POST /api/contracts                  - Create a contract
 * - PUT  /api/contracts/{id}             - Replace the editable fields; deactivates via isActive
 * - GET  /api/contracts/{id}/instalments - The contract's expected payments, status derived
 *
 * **There is deliberately no DELETE.** A contract deactivates through PUT's `isActive`: an
 * instalment already allocated against is history, and `ON DELETE CASCADE` would take it with the
 * contract.
 */
package org.example.project.features.contract.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.example.project.core.domain.dto.contract.ContractDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.contract.domain.service.ContractService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject

fun Route.contractRoutes() {
    val contractService by inject<ContractService>()

    // CRITICAL: Single authenticate wrapper for all endpoints
    authenticate("auth-jwt") {
        route("/api/contracts") {
            /**
             * List recurring obligations.
             *
             * Description: Contracts ordered by name. Set includeInactive to also return the ones an operator has switched off; a contract is never deleted, because an instalment already allocated against is history. The search parameter matches name or description case-insensitively; a blank search is ignored rather than returning nothing.
             *
             * Tag: contracts
             * Response: 200 The contract list with a total count.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get {
                val includeInactive =
                    call.request.queryParameters["includeInactive"]?.toBooleanStrictOrNull() ?: false
                val search = call.request.queryParameters["search"]
                call.respond(HttpStatusCode.OK, contractService.list(includeInactive, search))
            }

            // MUST stay above `get("/{id}")`: Ktor matches in declaration order, so with the two
            // swapped "alerts" is parsed as a contract id and every call 400s. This comment sits
            // above the KDoc rather than below it — anything between the KDoc and the handler
            // detaches the two, and the OpenAPI generator then reports the route as undocumented.
            /**
             * List what every active contract is complaining about.
             *
             * Description: A missing payment, a missing invoice or an amount outside its tolerance band, across every active contract. Derived on read, so an alert disappears by itself once the situation is resolved; a deactivated contract raises none.
             *
             * Tag: contracts
             * Response: 200 The alert list with a total count.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get("/alerts") {
                call.respond(HttpStatusCode.OK, contractService.alerts())
            }

            /**
             * Fetch one contract.
             *
             * Description: Returns the contract with its supplier, its schedule and its expected amount.
             *
             * Tag: contracts
             * Path: id Contract identifier.
             * Response: 200 The contract.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No contract with that id.
             */
            get("/{id}") {
                call.respond(HttpStatusCode.OK, contractService.get(call.longParam("id")))
            }

            /**
             * Create a recurring obligation.
             *
             * Description: Requires MANAGER role. The shop is resolved server-side. A dueDay outside 1..31 is rejected here rather than surfacing from the nightly instalment job.
             *
             * Tag: contracts
             * Response: 201 The created contract.
             * Response: 400 The body failed validation — blank name, dueDay outside 1..31, unknown interval, or an unparseable amount or date.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            post {
                val dto = contractService.create(call.receive<ContractDto.CreateRequestDto>())
                call.respond(HttpStatusCode.Created, dto)
            }

            /**
             * Replace a contract's editable fields.
             *
             * Description: Requires MANAGER role. A full replace, not a patch — an omitted optional field is written as null. Deactivation rides on isActive; there is no DELETE.
             *
             * Tag: contracts
             * Path: id Contract identifier.
             * Response: 200 The updated contract.
             * Response: 400 id is not a number, or the body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No contract with that id.
             */
            put("/{id}") {
                val dto =
                    contractService.update(
                        call.longParam("id"),
                        call.receive<ContractDto.UpdateRequestDto>(),
                    )
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * List a contract's expected payments.
             *
             * Description: Instalments due date ascending. Each status is derived at read time from the residual and the due date — the stored column holds only OPEN or WAIVED.
             *
             * Tag: contracts
             * Path: id Contract identifier.
             * Response: 200 The instalment list with a total count.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No contract with that id.
             */
            get("/{id}/instalments") {
                call.respond(HttpStatusCode.OK, contractService.instalments(call.longParam("id")))
            }
        }
    }
}

private fun ApplicationCall.longParam(name: String): Long =
    parameters[name]?.toLongOrNull()
        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid $name")
