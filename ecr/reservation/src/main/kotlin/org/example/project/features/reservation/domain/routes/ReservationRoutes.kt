package org.example.project.features.reservation.domain.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.core.domain.dto.reservation.ReservationDto
import org.example.project.core.domain.enums.reservation.ReservationStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.core.security.getEmployeeIdFromJwt
import org.example.project.features.reservation.domain.service.ReservationService
import org.example.project.features.reservation.domain.service.TableAssignmentService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject

fun Route.reservationRoutes() {
    val service by inject<ReservationService>()
    val assignmentService by inject<TableAssignmentService>()

    authenticate("auth-jwt") {
        route("/api/reservations") {
            /**
             * Search reservations.
             *
             * Description: Free-text lookup by guest name or contact across all dates — the counterpart to the date-bound list. The term comes from the required `q` query parameter. `limit` is clamped to 1–100 (default 50) and `offset` defaults to 0.
             *
             * Tag: reservations
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
                        ?.coerceIn(1, 100) ?: 50
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                val data = service.search(query, limit, offset)
                call.respond(HttpStatusCode.OK, data)
            }

            /**
             * Check reservation availability.
             *
             * Description: Reports what can still be booked on a date, taking the reservation rules and existing bookings into account. `date` is required; `time` and `partySize` narrow the answer to a specific slot.
             *
             * Tag: reservations
             * Response: 400 The date query parameter is missing or blank.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/availability") {
                val date =
                    call.request.queryParameters["date"]
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "date query parameter is required",
                        )
                if (date.isBlank()) {
                    throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.BadRequest,
                        "date query parameter is required",
                    )
                }
                val time = call.request.queryParameters["time"]
                val partySize = call.request.queryParameters["partySize"]?.toIntOrNull()

                val data = service.checkAvailability(date, time, partySize)
                call.respond(HttpStatusCode.OK, data)
            }

            /**
             * List reservations for a date.
             *
             * Description: The day view. The `date` query parameter is required — reservations are always read a day at a time. `status` narrows to one reservation status, `limit` defaults to 50 and is capped at 100, and `offset` defaults to 0.
             *
             * Tag: reservations
             * Response: 400 date is missing, or status is not a known reservation status.
             * Response: 401 Missing or invalid bearer token.
             */
            get {
                val date = call.request.queryParameters["date"]
                if (date.isNullOrBlank()) {
                    throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.BadRequest,
                        "date query parameter is required",
                    )
                }

                val statusParam = call.request.queryParameters["status"]
                val status =
                    if (!statusParam.isNullOrBlank()) {
                        try {
                            ReservationStatus.valueOf(statusParam)
                        } catch (e: IllegalArgumentException) {
                            throw PosException(
                                PosErrorCode.INVALID_REQUEST,
                                HttpStatusCode.BadRequest,
                                "Invalid status value: $statusParam",
                            )
                        }
                    } else {
                        null
                    }

                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceAtMost(100)
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                val data = service.getByDate(date, status, limit, offset)
                call.respond(HttpStatusCode.OK, data)
            }

            /**
             * Get a reservation by ID.
             *
             * Tag: reservations
             * Path: id Reservation identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No reservation with that id.
             */
            get("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid reservation ID",
                        )
                val data = service.getById(id)
                call.respond(HttpStatusCode.OK, data)
            }

            /**
             * Create a reservation.
             *
             * Description: Books a party for a date and time. The creating employee is taken from the JWT. Tables are not assigned here — use the auto-assign or assign-tables routes afterwards.
             *
             * Tag: reservations
             * Response: 400 The body is malformed or failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 409 The requested slot is not available under the reservation rules.
             */
            post {
                val employeeId = call.getEmployeeIdFromJwt()
                val request =
                    try {
                        call.receive<ReservationDto.CreateRequestDto>()
                    } catch (e: Exception) {
                        throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid request format",
                        )
                    }
                val data = service.create(employeeId, request)
                call.respond(HttpStatusCode.Created, data)
            }

            /**
             * Update a reservation.
             *
             * Description: Only the fields present in the body are changed. Moving a reservation to a different time re-checks availability.
             *
             * Tag: reservations
             * Path: id Reservation identifier.
             * Response: 400 id is not a number, or the body is malformed or failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No reservation with that id.
             * Response: 409 The new slot is not available under the reservation rules.
             */
            put("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid reservation ID",
                        )
                val employeeId = call.getEmployeeIdFromJwt()
                val request =
                    try {
                        call.receive<ReservationDto.UpdateRequestDto>()
                    } catch (e: Exception) {
                        throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid request format",
                        )
                    }
                val data = service.update(id, employeeId, request)
                call.respond(HttpStatusCode.OK, data)
            }

            /**
             * Update a reservation's status.
             *
             * Description: Narrow update for the floor workflow — seated, no-show, cancelled — without touching the booking itself. The acting employee is taken from the JWT.
             *
             * Tag: reservations
             * Path: id Reservation identifier.
             * Response: 400 id is not a number, or the status is not a known value.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No reservation with that id.
             * Response: 409 That status transition is not allowed from the current one.
             */
            put("/{id}/status") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid reservation ID",
                        )
                val employeeId = call.getEmployeeIdFromJwt()
                val request =
                    try {
                        call.receive<ReservationDto.UpdateStatusRequestDto>()
                    } catch (e: Exception) {
                        throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid request format",
                        )
                    }
                val data = service.updateStatus(id, employeeId, request)
                call.respond(HttpStatusCode.OK, data)
            }

            /**
             * Delete a reservation.
             *
             * Description: Requires MANAGER role, checked inline here. Removes the booking and frees any tables assigned to it. Cancelling via the status route is usually preferable, since it keeps the record.
             *
             * Tag: reservations
             * Path: id Reservation identifier.
             * Response: 204 Deleted. No body.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No reservation with that id.
             */
            delete("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid reservation ID",
                        )
                service.delete(id)
                call.respond(HttpStatusCode.NoContent)
            }

            /**
             * Auto-assign tables to several reservations.
             *
             * Description: Runs the table allocator over the listed reservations in one pass, which packs a service better than assigning them one at a time. The response reports what each reservation got, including the ones it could not seat.
             *
             * Tag: reservations
             * Response: 400 The body is malformed or failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 One of the reservations does not exist.
             */
            post("/bulk-auto-assign") {
                val employeeId = call.getEmployeeIdFromJwt()
                val request =
                    try {
                        call.receive<ReservationDto.BulkAutoAssignRequestDto>()
                    } catch (e: Exception) {
                        throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid request format",
                        )
                    }
                val data = assignmentService.bulkAutoAssign(request.reservationIds, employeeId)
                call.respond(HttpStatusCode.OK, data)
            }

            /**
             * Auto-assign tables to one reservation.
             *
             * Description: Lets the allocator pick tables for this booking from what is free at its sitting.
             *
             * Tag: reservations
             * Path: id Reservation identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No reservation with that id.
             * Response: 409 No combination of free tables fits the party.
             */
            post("/{id}/auto-assign") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid reservation ID",
                        )
                val employeeId = call.getEmployeeIdFromJwt()
                val data = assignmentService.autoAssign(id, employeeId)
                call.respond(HttpStatusCode.OK, data)
            }

            /**
             * Assign specific tables to a reservation.
             *
             * Description: Manual override of the allocator — the operator names the tables. The acting employee is taken from the JWT.
             *
             * Tag: reservations
             * Path: id Reservation identifier.
             * Response: 400 id is not a number, or the body is malformed or failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No reservation with that id, or one of the tables does not exist.
             * Response: 409 A named table is already assigned at that sitting.
             */
            post("/{id}/assign-tables") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid reservation ID",
                        )
                val employeeId = call.getEmployeeIdFromJwt()
                val request =
                    try {
                        call.receive<ReservationDto.ManualAssignRequestDto>()
                    } catch (e: Exception) {
                        throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid request format",
                        )
                    }
                val data = assignmentService.manualAssign(id, employeeId, request)
                call.respond(HttpStatusCode.OK, data)
            }

            /**
             * Unassign all tables from a reservation.
             *
             * Description: Requires MANAGER role, checked inline here. Frees every table held by the booking, leaving the reservation itself in place.
             *
             * Tag: reservations
             * Path: id Reservation identifier.
             * Response: 204 Unassigned. No body.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No reservation with that id.
             */
            delete("/{id}/unassign-tables") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid reservation ID",
                        )
                assignmentService.unassignAll(id)
                call.respond(HttpStatusCode.NoContent)
            }

            /**
             * List a reservation's assigned tables.
             *
             * Tag: reservations
             * Path: id Reservation identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No reservation with that id.
             */
            get("/{id}/tables") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid reservation ID",
                        )
                val data = assignmentService.getAssignments(id)
                call.respond(HttpStatusCode.OK, data)
            }
        }
    }
}
