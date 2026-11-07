package org.example.project.features.openinghours.domain.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.core.domain.dto.openinghours.OpeningHoursDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.openinghours.domain.service.OpeningHoursService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject

/**
 * Opening-hours routes. Weekdays in this API are **0-6 (Monday=0 .. Sunday=6)**; employee work
 * schedules ([org.example.project.features.employee.domain.routes.workScheduleRoutes]) use 1-7.
 */
fun Route.openingHoursRoutes() {
    val service by inject<OpeningHoursService>()

    authenticate("auth-jwt") {
        route("/api/opening-hours") {
            /**
             * List the weekly opening hours.
             *
             * Description: The shop's regular hours per weekday; `dayOfWeek` counts 0 = Monday to 6 = Sunday (employee work schedules count 1 = Monday to 7 = Sunday). Any authenticated employee may read them — they drive reservation availability and the closed-shop warnings.
             *
             * Tag: opening-hours
             * Response: 401 Missing or invalid bearer token.
             */
            get {
                val dtos = service.getAll()
                call.respond(HttpStatusCode.OK, dtos)
            }

            /**
             * Replace the weekly opening hours.
             *
             * Description: Requires MANAGER role, checked inline here. `dayOfWeek` is 0 = Monday to 6 = Sunday (employee work schedules use 1-7). The submitted week replaces the stored one in a single update, so a partial body clears the days it omits.
             *
             * Tag: opening-hours
             * Response: 400 The request body is malformed or failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             */
            put {
                val request =
                    try {
                        call.receive<OpeningHoursDto.BulkUpdateRequestDto>()
                    } catch (e: Exception) {
                        throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "Invalid request format",
                        )
                    }
                val dtos = service.bulkUpdate(request)
                call.respond(HttpStatusCode.OK, dtos)
            }

            route("/overrides") {
                /**
                 * List opening-hours overrides for a period.
                 *
                 * Description: Overrides are the exceptions to the weekly pattern — holidays, private functions, early closes. Both `startDate` and `endDate` query parameters are required.
                 *
                 * Tag: opening-hours
                 * Response: 400 startDate or endDate is missing or blank.
                 * Response: 401 Missing or invalid bearer token.
                 */
                get {
                    val startDate = call.request.queryParameters["startDate"]
                    val endDate = call.request.queryParameters["endDate"]
                    if (startDate.isNullOrBlank() || endDate.isNullOrBlank()) {
                        throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "startDate and endDate query parameters are required",
                        )
                    }
                    val dtos = service.getOverridesByDateRange(startDate, endDate)
                    call.respond(HttpStatusCode.OK, dtos)
                }

                /**
                 * Create an opening-hours override.
                 *
                 * Description: Requires MANAGER role, checked inline here. Overrides the weekly pattern for one date — a holiday closure or a one-off late night.
                 *
                 * Tag: opening-hours
                 * Response: 400 The request body is malformed or failed validation.
                 * Response: 401 Missing or invalid bearer token.
                 * Response: 403 Caller lacks the capability this route requires.
                 * Response: 409 An override already exists for that date.
                 */
                post {
                    val request =
                        try {
                            call.receive<OpeningHoursDto.CreateOverrideRequestDto>()
                        } catch (e: Exception) {
                            throw PosException(
                                PosErrorCode.VALIDATION_FAILED,
                                HttpStatusCode.BadRequest,
                                "Invalid request format",
                            )
                        }
                    val dto = service.createOverride(request)
                    call.respond(HttpStatusCode.Created, dto)
                }

                /**
                 * Update an opening-hours override.
                 *
                 * Description: Requires MANAGER role, checked inline here.
                 *
                 * Tag: opening-hours
                 * Path: id Override identifier.
                 * Response: 400 id is not a number, or the body is malformed or failed validation.
                 * Response: 401 Missing or invalid bearer token.
                 * Response: 403 Caller lacks the capability this route requires.
                 * Response: 404 No override with that id.
                 */
                put("/{id}") {
                    val id =
                        call.parameters["id"]?.toLongOrNull()
                            ?: throw PosException(
                                PosErrorCode.VALIDATION_FAILED,
                                HttpStatusCode.BadRequest,
                                "Invalid override ID",
                            )
                    val request =
                        try {
                            call.receive<OpeningHoursDto.UpdateOverrideRequestDto>()
                        } catch (e: Exception) {
                            throw PosException(
                                PosErrorCode.VALIDATION_FAILED,
                                HttpStatusCode.BadRequest,
                                "Invalid request format",
                            )
                        }
                    val dto = service.updateOverride(id, request)
                    call.respond(HttpStatusCode.OK, dto)
                }

                /**
                 * Delete an opening-hours override.
                 *
                 * Description: Requires MANAGER role, checked inline here. The weekly pattern applies again on that date.
                 *
                 * Tag: opening-hours
                 * Path: id Override identifier.
                 * Response: 204 Deleted. No body.
                 * Response: 400 id is not a number.
                 * Response: 401 Missing or invalid bearer token.
                 * Response: 403 Caller lacks the capability this route requires.
                 * Response: 404 No override with that id.
                 */
                delete("/{id}") {
                    val id =
                        call.parameters["id"]?.toLongOrNull()
                            ?: throw PosException(
                                PosErrorCode.VALIDATION_FAILED,
                                HttpStatusCode.BadRequest,
                                "Invalid override ID",
                            )
                    service.deleteOverride(id)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}
