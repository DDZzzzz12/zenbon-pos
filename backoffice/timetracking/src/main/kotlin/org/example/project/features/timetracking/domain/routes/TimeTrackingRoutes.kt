package org.example.project.features.timetracking.domain.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.number
import org.example.project.core.domain.dto.timetracking.TimeEntryDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.error.PosErrorCode
import org.example.project.core.security.OWNERSHIP_DENIED_STATUS
import org.example.project.core.security.getEmployeeIdFromJwt
import org.example.project.core.security.heldCapabilities
import org.example.project.core.security.isSelfOrHolds
import org.example.project.features.timetracking.domain.service.TimeTrackingService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject

fun Routing.timeTrackingRoutes() {
    val service by inject<TimeTrackingService>()

    authenticate("auth-jwt") {
        route("/api/timetracking") {
            post("/clock-in") {
                val request = call.receive<TimeEntryDto.ClockInRequestDto>()
                call.respond(HttpStatusCode.Created, service.clockIn(request))
            }

            post("/clock-out") {
                val request = call.receive<TimeEntryDto.ClockOutRequestDto>()
                call.respond(HttpStatusCode.OK, service.clockOut(request))
            }

            post("/break/start") {
                val request = call.receive<TimeEntryDto.BreakStartRequestDto>()
                call.respond(HttpStatusCode.OK, service.startBreak(request))
            }

            post("/break/end") {
                val request = call.receive<TimeEntryDto.BreakEndRequestDto>()
                call.respond(HttpStatusCode.OK, service.endBreak(request))
            }

            get("/active") {
                // The ROUTE stays SELF_READ — everyone may ask who is clocked in, including a
                // station. TIMETRACKING_ALL_READ decides only whether the answer is the whole board
                // or your own shift, which a route rule cannot express.
                call.respond(
                    HttpStatusCode.OK,
                    service.getActiveShifts(
                        employeeId = call.getEmployeeIdFromJwt() ?: "",
                        seesEveryone = Capability.TIMETRACKING_ALL_READ in call.heldCapabilities(),
                    ),
                )
            }

            get("/current") {
                val employeeId = call.getEmployeeIdFromJwt()
                val now =
                    org.example.project.utils.formatter
                        .currentTimeStamp()
                val year = call.request.queryParameters["year"]?.toIntOrNull() ?: now.year
                val month = call.request.queryParameters["month"]?.toIntOrNull() ?: now.month.number
                call.respond(HttpStatusCode.OK, service.getMyEntries(employeeId ?: "", year, month))
            }

            get("/my") {
                val employeeId = call.getEmployeeIdFromJwt()
                val year =
                    call.request.queryParameters["year"]?.toIntOrNull()
                        ?: throw validationFailed("year is required")
                val month =
                    call.request.queryParameters["month"]?.toIntOrNull()
                        ?: throw validationFailed("month is required")
                call.respond(HttpStatusCode.OK, service.getMyEntries(employeeId ?: "", year, month))
            }

            get("/overview") {
                val employeeId = call.getEmployeeIdFromJwt()
                val year =
                    call.request.queryParameters["year"]?.toIntOrNull()
                        ?: throw validationFailed("year is required")
                val month =
                    call.request.queryParameters["month"]?.toIntOrNull()
                        ?: throw validationFailed("month is required")
                call.respond(HttpStatusCode.OK, service.getMonthlyOverview(employeeId ?: "", year, month))
            }

            post("/manual") {
                val createdByEmployeeId = call.getEmployeeIdFromJwt()
                val request = call.receive<TimeEntryDto.ManualEntryRequestDto>()
                call.respond(HttpStatusCode.Created, service.createManualEntry(request, createdByEmployeeId ?: ""))
            }

            put("/{id}") {
                val id = call.parameters["id"] ?: throw validationFailed("id is required")
                val request = call.receive<TimeEntryDto.UpdateEntryRequestDto>()
                call.respond(HttpStatusCode.OK, service.updateEntry(id, request))
            }

            delete("/{id}") {
                val id = call.parameters["id"] ?: throw validationFailed("id is required")
                service.deleteEntry(id)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/team") {
                val year =
                    call.request.queryParameters["year"]?.toIntOrNull()
                        ?: throw validationFailed("year is required")
                val month =
                    call.request.queryParameters["month"]?.toIntOrNull()
                        ?: throw validationFailed("month is required")
                call.respond(HttpStatusCode.OK, service.getTeamEntries(year, month))
            }

            // Per-employee entries (manager only)
            get("/employee/{employeeId}") {
                val employeeId =
                    call.parameters["employeeId"]
                        ?: throw validationFailed("employeeId is required")
                call.requireOwnTimesheetOrTeamReader(employeeId)
                val year =
                    call.request.queryParameters["year"]?.toIntOrNull()
                        ?: throw validationFailed("year is required")
                val month =
                    call.request.queryParameters["month"]?.toIntOrNull()
                        ?: throw validationFailed("month is required")
                call.respond(HttpStatusCode.OK, service.getMyEntries(employeeId, year, month))
            }

            // Per-employee monthly overview: own, or TIMETRACKING_READ for a colleague's.
            get("/employee/{employeeId}/overview") {
                val employeeId =
                    call.parameters["employeeId"]
                        ?: throw validationFailed("employeeId is required")
                call.requireOwnTimesheetOrTeamReader(employeeId)
                val year =
                    call.request.queryParameters["year"]?.toIntOrNull()
                        ?: throw validationFailed("year is required")
                val month =
                    call.request.queryParameters["month"]?.toIntOrNull()
                        ?: throw validationFailed("month is required")
                call.respond(HttpStatusCode.OK, service.getMonthlyOverview(employeeId, year, month))
            }

            get("/all") {
                val year =
                    call.request.queryParameters["year"]?.toIntOrNull()
                        ?: throw validationFailed("year is required")
                val month =
                    call.request.queryParameters["month"]?.toIntOrNull()
                        ?: throw validationFailed("month is required")
                call.respond(HttpStatusCode.OK, service.getAllEntries(year, month))
            }

            post("/my-entries-auth") {
                val request = call.receive<TimeEntryDto.AuthenticatedEntriesRequestDto>()
                call.respond(HttpStatusCode.OK, service.getAuthenticatedEntries(request))
            }
        }
    }
}

/**
 * The ownership half of `CapWithOwnership(SELF_READ | TIMETRACKING_READ, "own timesheet, ...")`.
 *
 * **Both callers of this were unguarded before Stage 1c.** The registry described them as "self or
 * manager" and the overview even carried a `// manager only` comment, but `AccessLevel.Custom` told
 * the plugin to skip enforcement and defer to the handler — and the handler checked nothing.
 *
 * Latent rather than live: this feature is de-integrated (`Routing.kt` does not call
 * `timeTrackingRoutes()`), so the routes 404 today. The guard is added now so that re-mounting the
 * feature does not also re-mount a hole that lets anyone read anyone's hours.
 *
 * Reads the capability set the plugin already resolved rather than resolving again, so there is
 * exactly one answer per request to "what does this caller hold".
 */
private suspend fun ApplicationCall.requireOwnTimesheetOrTeamReader(employeeId: String) {
    if (isSelfOrHolds(employeeId, Capability.TIMETRACKING_READ)) return
    throw PosException(
        PosErrorCode.AUTH_FORBIDDEN,
        OWNERSHIP_DENIED_STATUS,
        "Reading another employee's timesheet requires TIMETRACKING_READ",
    )
}

private fun validationFailed(msg: String) =
    PosException(
        PosErrorCode.VALIDATION_FAILED,
        HttpStatusCode.BadRequest,
        msg,
    )
