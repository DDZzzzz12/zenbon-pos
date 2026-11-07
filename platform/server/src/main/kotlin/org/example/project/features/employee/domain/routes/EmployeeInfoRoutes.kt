/**
 * API routes for EmployeeInfo management operations.
 *
 * Migrated to throws-style in Stage B Batch 10. The service throws PosException;
 * the global StatusPages plugin converts to application/problem+json.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.employee.domain.routes

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
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.employee.domain.service.EmployeeInfoService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getEmployeeIdFromJwt
import org.koin.ktor.ext.inject
import java.util.UUID

fun Routing.employeeInfoRoutes() {
    val employeeInfoService by inject<EmployeeInfoService>()

    authenticate("auth-jwt") {
        route("api/employee/info") {
            /**
             * Create an employee info record.
             *
             * Description: Requires MANAGER role. Employee info holds the personnel data kept beside the login account — contact details, employment terms and payroll identifiers.
             *
             * Tag: employee-info
             * Response: 400 The request body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 409 That employee already has an info record.
             */
            post {
                val request = call.receive<EmployeeInfoDto.CreateEmployeeInfoRequestDto>()
                val dto = employeeInfoService.createEmployeeInfo(request)
                call.respond(HttpStatusCode.Created, dto)
            }

            /**
             * Get my own employee info.
             *
             * Description: Self-read — the employee comes from the JWT, so any authenticated caller can see their own personnel record and no one else's.
             *
             * Tag: employee-info
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 404 The caller has no employee info record.
             */
            get("/me") {
                val employeeId = call.getEmployeeIdFromJwt()
                val dto = employeeInfoService.getEmployeeInfoByEmployeeId(UUID.fromString(employeeId))
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Update my own employee info.
             *
             * Description: Self-service update, limited to the fields an employee may maintain themselves such as contact details. Employment terms (hire date, job title, hourly rate, salary, contracted hours and period) stay manager-only: a body that sets or clears any of them is refused as a whole.
             *
             * Tag: employee-info
             * Response: 400 The request body failed validation.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 The body names an employment term (`SELF_MODIFICATION_FORBIDDEN`).
             * Response: 404 The caller has no employee info record.
             */
            put("/me") {
                val employeeId = call.getEmployeeIdFromJwt()
                val updateData = call.receive<EmployeeInfoDto.UpdateEmployeeInfoRequestDto>()
                val dto = employeeInfoService.updateOwnEmployeeInfo(UUID.fromString(employeeId), updateData)
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Get another employee's info.
             *
             * Description: Requires MANAGER role, since the record carries personal data.
             *
             * Tag: employee-info
             * Path: employeeId Employee identifier, a UUID.
             * Response: 400 employeeId is missing, or is not a valid UUID.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 That employee has no info record.
             */
            get("/by-employee/{employeeId}") {
                val employeeId = parseUuidParam("employeeId", call.parameters["employeeId"])
                val dto = employeeInfoService.getEmployeeInfoByEmployeeId(employeeId)
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Update another employee's info.
             *
             * Description: Requires MANAGER role. Unlike the self-service route, this may change employment terms and payroll fields. Only the fields present in the body are changed; a field sent as an empty string is cleared. The change is audited (`UPDATE_EMPLOYEE`, field names only).
             *
             * Tag: employee-info
             * Path: employeeId Employee identifier, a UUID.
             * Response: 400 employeeId is missing or not a valid UUID, or the body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 That employee has no info record.
             */
            put("/by-employee/{employeeId}") {
                val employeeId = parseUuidParam("employeeId", call.parameters["employeeId"])
                val updateData = call.receive<EmployeeInfoDto.UpdateEmployeeInfoRequestDto>()
                val dto =
                    employeeInfoService.updateEmployeeInfoByEmployeeId(
                        employeeId,
                        updateData,
                        actorId = call.getEmployeeIdFromJwt(),
                    )
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Delete an employee's info record.
             *
             * Description: Requires MANAGER role. Removes the personnel record; the login account itself is untouched and the employee can still sign in.
             *
             * Tag: employee-info
             * Path: employeeId Employee identifier, a UUID.
             * Response: 400 employeeId is missing, or is not a valid UUID.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 That employee has no info record.
             */
            delete("/by-employee/{employeeId}") {
                val employeeId = parseUuidParam("employeeId", call.parameters["employeeId"])
                employeeInfoService.deleteEmployeeInfoByEmployeeId(employeeId)
                call.respond(HttpStatusCode.OK, "Deleted")
            }

            /**
             * List employee info records.
             *
             * Description: Requires MANAGER role. Pages with `limit` (default 50) and `offset` (default 0).
             *
             * Tag: employee-info
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                call.respond(HttpStatusCode.OK, employeeInfoService.getAllEmployeeInfo(limit, offset))
            }
        }
    }
}

private fun validationFailed(msg: String) =
    PosException(
        PosErrorCode.VALIDATION_FAILED,
        HttpStatusCode.BadRequest,
        msg,
    )

private fun parseUuidParam(
    name: String,
    value: String?,
): UUID {
    if (value == null) throw validationFailed("$name is required")
    return try {
        UUID.fromString(value)
    } catch (e: IllegalArgumentException) {
        throw validationFailed("$name must be a valid UUID")
    }
}
