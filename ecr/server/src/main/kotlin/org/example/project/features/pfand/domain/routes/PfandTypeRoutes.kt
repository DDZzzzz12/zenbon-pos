/**
 * REST API routes for Pfand (deposit) type operations.
 *
 * Throws-style: the service throws PosException; the global StatusPages plugin
 * converts to application/problem+json.
 */
package org.example.project.features.pfand.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.example.project.core.domain.dto.pfand.PfandTypeDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.pfand.domain.service.PfandTypeService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject

fun Route.pfandTypeRoutes() {
    val service by inject<PfandTypeService>()

    authenticate("auth-jwt") {
        route("/api/pfand-types") {
            /**
             * List Pfand types.
             *
             * Description: Pfand types are the deposit tiers (bottle, crate) a product can carry, each with a deposit value and VAT class. Any authenticated employee may read them. Pass `includeInactive=true` to include retired tiers (default false).
             *
             * Tag: pfand-types
             * Response: 401 Missing or invalid bearer token.
             */
            get {
                val includeInactive = call.request.queryParameters["includeInactive"]?.toBoolean() ?: false
                call.respond(HttpStatusCode.OK, service.list(includeInactive))
            }

            /**
             * Create a Pfand type.
             *
             * Description: Requires the system-configuration permission or MANAGER role. Creating a tier also creates its two backing products — the deposit charge and the matching return — so it can be booked on a receipt. The VAT class must exist.
             *
             * Tag: pfand-types
             * Response: 400 The body failed validation, or the VAT class does not exist.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the system-configuration permission and is not a MANAGER.
             * Response: 409 A Pfand type with that name already exists.
             */
            post {
                val request = call.receive<PfandTypeDto.CreatePfandTypeRequestDto>()
                call.respond(HttpStatusCode.Created, service.create(request))
            }

            /**
             * Update a Pfand type.
             *
             * Description: Requires the system-configuration permission or MANAGER role. Renaming or repricing the tier also renames and reprices its two backing products, and deactivating it deactivates them too.
             *
             * Tag: pfand-types
             * Path: id Pfand type identifier.
             * Response: 400 id is not a number, the body failed validation, or the VAT class does not exist.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the system-configuration permission and is not a MANAGER.
             * Response: 404 No Pfand type with that id.
             */
            put("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid Pfand type ID")
                val request = call.receive<PfandTypeDto.UpdatePfandTypeRequestDto>()
                call.respond(HttpStatusCode.OK, service.update(id, request))
            }

            /**
             * Deactivate a Pfand type.
             *
             * Description: Requires the system-configuration permission or MANAGER role. Nothing is deleted — the tier and its two backing products are flagged inactive so past receipts keep resolving, and products can no longer be assigned to it.
             *
             * Tag: pfand-types
             * Path: id Pfand type identifier.
             * Response: 204 Deactivated. No body.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the system-configuration permission and is not a MANAGER.
             * Response: 404 No Pfand type with that id.
             */
            delete("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid Pfand type ID")
                service.deactivate(id)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
