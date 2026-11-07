package org.example.project.features.product.domain.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.core.domain.dto.product.ChoiceGroupDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.product.domain.service.ChoiceGroupService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getEmployeeIdFromJwt
import org.koin.ktor.ext.inject

fun Route.choiceGroupRoutes() {
    val service by inject<ChoiceGroupService>()
    authenticate("auth-jwt") {
        route("/api/choice-groups") {
            /**
             * List choice groups.
             *
             * Description: Choice groups are the option sets a product can offer — menu section, size, add-ons — each with its own options. Any authenticated employee may read them; the POS configurator needs them. Pass `includeInactive=true` to include deactivated groups (default false).
             *
             * Tag: choice-groups
             * Response: 401 Missing or invalid bearer token.
             */
            get {
                val includeInactive = call.request.queryParameters["includeInactive"]?.toBoolean() ?: false
                call.respond(HttpStatusCode.OK, service.getAll(includeInactive))
            }

            /**
             * Get choice groups changed since a timestamp.
             *
             * Description: Incremental sync feed for offline-capable clients — returns only the groups updated after the given timestamp.
             *
             * Tag: choice-groups
             * Path: lastSyncTime ISO-8601 timestamp of the client's last successful sync.
             * Response: 400 lastSyncTime is missing.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/sync/{lastSyncTime}") {
                val t =
                    call.parameters["lastSyncTime"]
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Last sync time is required")
                call.respond(HttpStatusCode.OK, service.getUpdatedAfter(t))
            }

            /**
             * Get a choice group by ID.
             *
             * Description: Returns the group together with its options and their price deltas.
             *
             * Tag: choice-groups
             * Path: id Choice group identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No choice group with that id.
             */
            get("/{id}") {
                val id =
                    call.parameters["id"]?.toIntOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid id")
                call.respond(HttpStatusCode.OK, service.getById(id))
            }

            /**
             * Create a choice group.
             *
             * Description: Requires MANAGER role. The group's options and their price deltas are created with it. Price deltas are exact decimals, sent as strings.
             *
             * Tag: choice-groups
             * Response: 400 The request body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            post {
                val uid = call.getEmployeeIdFromJwt()
                val req = call.receive<ChoiceGroupDto.CreateChoiceGroupRequestDto>()
                call.respond(HttpStatusCode.Created, service.create(uid, req))
            }

            /**
             * Update a choice group.
             *
             * Description: Requires MANAGER role. Updates the group and its option list. Changed price deltas apply to future orders only — order lines already booked keep the delta snapshotted at the time of sale.
             *
             * Tag: choice-groups
             * Path: id Choice group identifier.
             * Response: 400 id is not a number, or the body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No choice group with that id.
             */
            put("/{id}") {
                val id =
                    call.parameters["id"]?.toIntOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid id")
                val uid = call.getEmployeeIdFromJwt()
                val req = call.receive<ChoiceGroupDto.UpdateChoiceGroupRequestDto>()
                call.respond(HttpStatusCode.OK, service.update(uid, id, req))
            }

            /**
             * Delete a choice group.
             *
             * Description: Requires MANAGER role. Groups still attached to products are deactivated rather than removed, so historical order lines keep resolving their chosen options.
             *
             * Tag: choice-groups
             * Path: id Choice group identifier.
             * Response: 204 Deleted. No body.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No choice group with that id.
             */
            delete("/{id}") {
                val id =
                    call.parameters["id"]?.toIntOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid id")
                val uid = call.getEmployeeIdFromJwt()
                service.delete(uid, id)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
