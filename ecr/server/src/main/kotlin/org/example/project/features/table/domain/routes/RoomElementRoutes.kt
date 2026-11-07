/**
 * Room element API routes.
 *
 * Routes delegate directly to [RoomElementService] which throws [PosException]
 * on errors; the global StatusPages handler converts to application/problem+json.
 */
package org.example.project.features.table.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.example.project.core.domain.dto.table.RoomElementDto
import org.example.project.core.domain.enums.room.RoomElementType
import org.example.project.core.error.PosErrorCode
import org.example.project.features.table.domain.service.RoomElementService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getEmployeeIdFromJwt
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("RoomElementRoutes")

private fun invalidId(name: String = "id"): PosException =
    PosException(
        PosErrorCode.VALIDATION_FAILED,
        HttpStatusCode.BadRequest,
        "Invalid $name",
    )

fun Routing.roomElementRoutes() {
    val roomElementService by inject<RoomElementService>()

    authenticate("auth-jwt") {
        route("/api/room-elements") {
            /**
             * List room elements.
             *
             * Description: Room elements are the non-table furniture on the floor plan — walls, plants, bars. Any authenticated employee may read them to render the plan. Optional query parameters narrow the result — `type` to one element type, `locationTypeId` to one room, plus `limit` and `offset`.
             *
             * Tag: room-elements
             * Response: 400 type is not a valid element type, or locationTypeId is not a number.
             * Response: 401 Missing or invalid bearer token.
             */
            get {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()

                val elementType =
                    call.request.queryParameters["type"]?.let { typeParam ->
                        try {
                            RoomElementType.valueOf(typeParam.uppercase())
                        } catch (e: IllegalArgumentException) {
                            throw PosException(
                                PosErrorCode.VALIDATION_FAILED,
                                HttpStatusCode.BadRequest,
                                "Invalid type parameter. Valid values: ${RoomElementType.entries.joinToString()}",
                            )
                        }
                    }

                val locationTypeId =
                    call.request.queryParameters["locationTypeId"]?.let { param ->
                        param.toLongOrNull() ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "Invalid locationTypeId parameter: must be a numeric ID",
                        )
                    }

                logger.debug(
                    "Getting room elements with limit=$limit, offset=$offset, type=$elementType, locationTypeId=$locationTypeId",
                )
                call.respond(
                    HttpStatusCode.OK,
                    roomElementService.getAllElements(limit, offset, elementType, locationTypeId),
                )
            }

            /**
             * Create a room element.
             *
             * Description: Requires MANAGER role. Places a new element on the floor plan at the given position and size. The acting employee is taken from the JWT.
             *
             * Tag: room-elements
             * Response: 400 The body failed validation, or the location type is unknown.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            post {
                val userId = call.getEmployeeIdFromJwt()
                val request = call.receive<RoomElementDto.CreateRoomElementRequestDto>()
                logger.info(
                    "Creating room element: ${request.elementType} at (${request.positionX}, ${request.positionY})",
                )
                call.respond(HttpStatusCode.Created, roomElementService.createElement(userId, request))
            }

            /**
             * Get a room element by ID.
             *
             * Tag: room-elements
             * Path: id Room element identifier. Must be a positive number.
             * Response: 400 id is not a positive number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No room element with that id.
             */
            get("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw invalidId("element ID")
                if (id <= 0) throw invalidId("element ID")
                call.respond(HttpStatusCode.OK, roomElementService.getElementById(id))
            }

            /**
             * Update a room element.
             *
             * Description: Requires MANAGER role. Only the fields present in the body are changed.
             *
             * Tag: room-elements
             * Path: id Room element identifier. Must be a positive number.
             * Response: 400 id is not a positive number, or the body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No room element with that id.
             */
            put("/{id}") {
                val userId = call.getEmployeeIdFromJwt()
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw invalidId("element ID")
                if (id <= 0) throw invalidId("element ID")
                val request = call.receive<RoomElementDto.UpdateRoomElementRequestDto>()
                logger.info("Updating room element: $id")
                call.respond(HttpStatusCode.OK, roomElementService.updateElement(userId, id, request))
            }

            /**
             * Delete a room element.
             *
             * Description: Requires MANAGER role. Room elements carry no transactional data, so this is a hard delete.
             *
             * Tag: room-elements
             * Path: id Room element identifier. Must be a positive number.
             * Response: 400 id is not a positive number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No room element with that id.
             */
            delete("/{id}") {
                val userId = call.getEmployeeIdFromJwt()
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw invalidId("element ID")
                if (id <= 0) throw invalidId("element ID")
                logger.info("Deleting room element: $id")
                roomElementService.deleteElement(userId, id)
                call.respond(HttpStatusCode.OK, "Room element deleted successfully")
            }

            /**
             * Move a room element.
             *
             * Description: Requires MANAGER role. Narrow update for the floor-plan editor's drag gesture — it writes only the x/y position.
             *
             * Tag: room-elements
             * Path: id Room element identifier. Must be a positive number.
             * Response: 400 id is not a positive number, or the body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No room element with that id.
             */
            patch("/{id}/position") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw invalidId("element ID")
                if (id <= 0) throw invalidId("element ID")
                val request = call.receive<RoomElementDto.UpdateRoomElementPositionRequestDto>()
                logger.info(
                    "Updating room element position: $id to (${request.positionX}, ${request.positionY})",
                )
                call.respond(HttpStatusCode.OK, roomElementService.updateElementPosition(id, request))
            }

            /**
             * Resize a room element.
             *
             * Description: Requires MANAGER role. Narrow update for the floor-plan editor's resize handles — it writes only width and height.
             *
             * Tag: room-elements
             * Path: id Room element identifier. Must be a positive number.
             * Response: 400 id is not a positive number, or the body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No room element with that id.
             */
            patch("/{id}/dimensions") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw invalidId("element ID")
                if (id <= 0) throw invalidId("element ID")
                val request = call.receive<RoomElementDto.UpdateRoomElementDimensionsRequestDto>()
                logger.info(
                    "Updating room element dimensions: $id to ${request.width}x${request.height}",
                )
                call.respond(HttpStatusCode.OK, roomElementService.updateElementDimensions(id, request))
            }

            /**
             * List room elements of one type.
             *
             * Description: Read-only floor-plan lookup, open to any authenticated employee. The type is matched case-insensitively.
             *
             * Tag: room-elements
             * Path: type Room element type, for example WALL or PLANT.
             * Response: 400 type is not a valid room element type.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/by-type/{type}") {
                val typeParam = call.parameters["type"]?.uppercase()
                val elementType =
                    try {
                        RoomElementType.valueOf(typeParam ?: "")
                    } catch (e: IllegalArgumentException) {
                        throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "Invalid type. Valid values: ${RoomElementType.entries.joinToString()}",
                        )
                    }
                call.respond(HttpStatusCode.OK, roomElementService.getElementsByType(elementType))
            }

            /**
             * List the room elements of one room.
             *
             * Description: Read-only floor-plan lookup, open to any authenticated employee — this is what the client fetches when switching rooms.
             *
             * Tag: room-elements
             * Path: locationTypeId Location type identifier of the room. Must be a positive number.
             * Response: 400 locationTypeId is not a positive number.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/by-location/{locationTypeId}") {
                val locationTypeId =
                    call.parameters["locationTypeId"]?.toLongOrNull()
                        ?: throw invalidId("locationTypeId")
                if (locationTypeId <= 0) throw invalidId("locationTypeId")
                call.respond(
                    HttpStatusCode.OK,
                    roomElementService.getElementsByLocationTypeId(locationTypeId),
                )
            }

            /**
             * Get room elements changed since a timestamp.
             *
             * Description: Incremental sync feed for offline-capable clients — returns only the elements updated after the given timestamp.
             *
             * Tag: room-elements
             * Path: lastSyncTime ISO-8601 timestamp of the client's last successful sync.
             * Response: 400 lastSyncTime is missing or blank.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/sync/{lastSyncTime}") {
                val lastSyncTime = call.parameters["lastSyncTime"]
                if (lastSyncTime.isNullOrBlank()) {
                    throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.BadRequest,
                        "Last sync time is required",
                    )
                }
                call.respond(HttpStatusCode.OK, roomElementService.getElementsUpdatedAfter(lastSyncTime))
            }
        }
    }
}
