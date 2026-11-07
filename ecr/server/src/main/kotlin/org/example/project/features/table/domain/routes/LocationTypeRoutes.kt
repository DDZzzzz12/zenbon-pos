package org.example.project.features.table.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.example.project.core.domain.dto.table.LocationTypeDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.table.domain.service.LocationTypeService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject

fun Routing.locationTypeRoutes() {
    val locationTypeService by inject<LocationTypeService>()

    authenticate("auth-jwt") {
        route("/api/location-types") {
            /**
             * List location types.
             *
             * Description: Location types are the rooms or areas a table can sit in — terrace, bar, upstairs. Any authenticated employee may read them, since the floor plan is organised by room.
             *
             * Tag: location-types
             * Response: 401 Missing or invalid bearer token.
             */
            get {
                call.respond(HttpStatusCode.OK, locationTypeService.getAll())
            }

            /**
             * Get a location type by ID.
             *
             * Tag: location-types
             * Path: id Location type identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No location type with that id.
             */
            get("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "Invalid ID",
                        )
                call.respond(HttpStatusCode.OK, locationTypeService.getById(id))
            }

            /**
             * Create a location type.
             *
             * Description: Requires the capability RoutePermissions names for this route. Adds a room to the floor plan; tables are assigned to it separately.
             *
             * Tag: location-types
             * Response: 400 The request body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 409 A location type with that name already exists.
             */
            post {
                val request = call.receive<LocationTypeDto.CreateRequestDto>()
                call.respond(HttpStatusCode.Created, locationTypeService.create(request))
            }

            /**
             * Update a location type.
             *
             * Description: Requires the capability RoutePermissions names for this route. Only the fields present in the body are changed.
             *
             * Tag: location-types
             * Path: id Location type identifier.
             * Response: 400 id is not a number, or the body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No location type with that id.
             */
            put("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "Invalid ID",
                        )
                val request = call.receive<LocationTypeDto.UpdateRequestDto>()
                call.respond(HttpStatusCode.OK, locationTypeService.update(id, request))
            }

            /**
             * Delete a location type.
             *
             * Description: Requires the capability RoutePermissions names for this route. A room still holding tables or room elements cannot be removed — move them out first.
             *
             * Tag: location-types
             * Path: id Location type identifier.
             * Response: 204 Deleted. No body.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No location type with that id.
             * Response: 409 Tables or room elements still reference this location type.
             */
            delete("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "Invalid ID",
                        )
                locationTypeService.delete(id)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
