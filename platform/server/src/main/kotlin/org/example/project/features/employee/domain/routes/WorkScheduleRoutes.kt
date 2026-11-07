/**
 * Routes for the weekly work schedule of an employee.
 *
 * Weekdays in this API are **1-7 (Monday=1 .. Sunday=7)**; shop opening hours
 * (`OpeningHoursRoutes`) use 0-6 (Monday=0 .. Sunday=6).
 *
 * Migrated to throws-style in Stage B Batch 10.
 */
package org.example.project.features.employee.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.example.project.core.domain.dto.employee.WorkScheduleDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.error.PosErrorCode
import org.example.project.core.security.OWNERSHIP_DENIED_STATUS
import org.example.project.core.security.isSelfOrHolds
import org.example.project.features.employee.domain.service.WorkScheduleService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject
import java.util.UUID

fun Routing.workScheduleRoutes() {
    val workScheduleService by inject<WorkScheduleService>()

    authenticate("auth-jwt") {
        route("api/employees/{employeeId}/schedule") {
            /**
             * Get an employee's weekly schedule.
             *
             * Description: Self or manager — an employee may read their own schedule; reading a colleague's requires MANAGER role. Returns all seven days, including the ones with no shift.
             *
             * Tag: work-schedule
             * Path: employeeId Employee identifier, a UUID.
             * Response: 400 employeeId is missing, or is not a valid UUID.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is reading another employee's schedule and is not a MANAGER.
             * Response: 404 No employee with that id.
             */
            get {
                val employeeIdParam = call.parameters["employeeId"]
                // Unguarded before Stage 1c. The KDoc above already promised "403 Caller is reading
                // another employee's schedule and is not a MANAGER" — the rule said Custom, so the
                // plugin skipped enforcement, and nothing here picked it up. Any authenticated
                // caller could read anyone's shifts.
                if (!call.isSelfOrHolds(employeeIdParam, Capability.EMPLOYEE_READ)) {
                    throw PosException(
                        PosErrorCode.AUTH_FORBIDDEN,
                        OWNERSHIP_DENIED_STATUS,
                        "Reading another employee's schedule requires EMPLOYEE_READ",
                    )
                }
                val employeeId = parseEmployeeId(employeeIdParam)
                call.respond(HttpStatusCode.OK, workScheduleService.getWeeklySchedule(employeeId))
            }

            /**
             * Update one day of an employee's schedule.
             *
             * Description: Requires MANAGER role. Narrow update for editing a single day without resubmitting the week.
             *
             * Tag: work-schedule
             * Path: employeeId Employee identifier, a UUID.
             * Response: 400 employeeId is missing or not a valid UUID, or the day or times failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No employee with that id.
             */
            put("day") {
                val employeeId = parseEmployeeId(call.parameters["employeeId"])
                val request = call.receive<WorkScheduleDto.UpdateDayRequestDto>()
                call.respond(HttpStatusCode.OK, workScheduleService.updateDaySchedule(employeeId, request))
            }

            /**
             * Replace an employee's weekly schedule.
             *
             * Description: Requires MANAGER role. The submitted week replaces the stored one — days omitted from the body are cleared, not preserved.
             *
             * Tag: work-schedule
             * Path: employeeId Employee identifier, a UUID.
             * Response: 400 employeeId is missing or not a valid UUID, or the week failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No employee with that id.
             */
            put {
                val employeeId = parseEmployeeId(call.parameters["employeeId"])
                val request = call.receive<WorkScheduleDto.UpdateWeekRequestDto>()
                call.respond(HttpStatusCode.OK, workScheduleService.updateWeekSchedule(employeeId, request))
            }
        }
    }
}

private fun parseEmployeeId(value: String?): UUID {
    if (value == null) {
        throw PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            "employeeId is required",
        )
    }
    return try {
        UUID.fromString(value)
    } catch (e: IllegalArgumentException) {
        throw PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            "employeeId must be a valid UUID",
        )
    }
}
