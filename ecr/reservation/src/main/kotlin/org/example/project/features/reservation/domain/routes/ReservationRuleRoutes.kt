package org.example.project.features.reservation.domain.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.core.domain.dto.reservation.ReservationRuleDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.reservation.domain.service.ReservationRuleService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject

fun Route.reservationRuleRoutes() {
    val service by inject<ReservationRuleService>()

    authenticate("auth-jwt") {
        route("/api/reservation-rules") {
            /**
             * List reservation rules for a period.
             *
             * Description: Rules govern when and how much can be booked — opening slots, party-size limits and blackout days. Both `startDate` and `endDate` query parameters are required.
             *
             * Tag: reservation-rules
             * Response: 400 startDate or endDate is missing or blank.
             * Response: 401 Missing or invalid bearer token.
             */
            get {
                val startDate = call.request.queryParameters["startDate"]
                val endDate = call.request.queryParameters["endDate"]

                if (startDate.isNullOrBlank() || endDate.isNullOrBlank()) {
                    throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.BadRequest,
                        "startDate and endDate query parameters are required",
                    )
                }

                val data = service.getByDateRange(startDate, endDate)
                call.respond(HttpStatusCode.OK, data)
            }

            /**
             * Get a reservation rule by ID.
             *
             * Tag: reservation-rules
             * Path: id Reservation rule identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No reservation rule with that id.
             */
            get("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid rule ID",
                        )
                val data = service.getById(id)
                call.respond(HttpStatusCode.OK, data)
            }

            /**
             * Create a reservation rule.
             *
             * Description: Requires MANAGER role, checked inline here. The rule applies to bookings made or moved after it exists — reservations already taken are not re-validated against it.
             *
             * Tag: reservation-rules
             * Response: 400 The body is malformed or failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             */
            post {
                val request =
                    try {
                        call.receive<ReservationRuleDto.CreateRequestDto>()
                    } catch (e: Exception) {
                        throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid request format",
                        )
                    }

                val data = service.create(request)
                call.respond(HttpStatusCode.Created, data)
            }

            /**
             * Update a reservation rule.
             *
             * Description: Requires MANAGER role, checked inline here. Only the fields present in the body are changed.
             *
             * Tag: reservation-rules
             * Path: id Reservation rule identifier.
             * Response: 400 id is not a number, or the body is malformed or failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No reservation rule with that id.
             */
            put("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid rule ID",
                        )

                val request =
                    try {
                        call.receive<ReservationRuleDto.UpdateRequestDto>()
                    } catch (e: Exception) {
                        throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid request format",
                        )
                    }

                val data = service.update(id, request)
                call.respond(HttpStatusCode.OK, data)
            }

            /**
             * Delete a reservation rule.
             *
             * Description: Requires MANAGER role, checked inline here. Bookings already accepted under the rule are unaffected.
             *
             * Tag: reservation-rules
             * Path: id Reservation rule identifier.
             * Response: 204 Deleted. No body.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No reservation rule with that id.
             */
            delete("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid rule ID",
                        )

                service.delete(id)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
