/**
 * REST API routes for Employee management operations.
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
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.employee.isManagerOrHigher
import org.example.project.core.error.PosErrorCode
import org.example.project.core.security.OWNERSHIP_DENIED_STATUS
import org.example.project.core.security.heldCapabilities
import org.example.project.core.security.isSelfOrHolds
import org.example.project.features.employee.domain.service.EmployeeService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getEmployeeIdFromJwt
import org.example.project.infrastructure.plugins.getTokenClaims
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import java.util.UUID

private val logger = LoggerFactory.getLogger("EmployeeRoutes")

fun Routing.employeeRoutes() {
    val employeeService by inject<EmployeeService>()
    authenticate("auth-jwt") {
        route("/api/employees") {
            /**
             * Get my own employee record.
             *
             * Description: Resolves the employee id from the JWT, so a caller can always read their own full record whatever their role.
             *
             * Tag: employees
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 404 The token references an employee that no longer exists.
             */
            get("/me") {
                val claim = call.getTokenClaims() ?: throw invalidCredentials()
                val dto = employeeService.getEmployeeById(UUID.fromString(claim.employeeId))
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * List active employees.
             *
             * Description: Open to any authenticated employee — the POS needs the roster to attribute a table or a tip to a colleague. Only active staff are returned.
             *
             * Tag: employees
             * Response: 401 Missing or invalid bearer token.
             */
            get("/info/active") {
                call.respond(HttpStatusCode.OK, employeeService.getActiveEmployees())
            }

            /**
             * List employee summaries.
             *
             * Description: Requires the EMPLOYEE_READ capability. Lightweight rows for the staff-management list, filtered by the requesting employee's own role (a role-hierarchy rule, not a capability one): an ADMIN sees every employee, admins included, read-only; anyone else sees only cashier and terminal-device rows. `displayName` (the HR first and last name) is filled only for a caller holding EMPLOYEE_READ, and is null for an employee without an HR record. Pages with `limit` (default 50) and `offset` (default 0), and `active=true|false` narrows by status.
             *
             * Tag: employees
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller does not hold EMPLOYEE_READ.
             */
            get("/summary") {
                val employeeId = call.getEmployeeIdFromJwt()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                val active = call.request.queryParameters["active"]?.toBooleanStrictOrNull()

                val dto =
                    employeeService.getEmployeeSummaries(
                        requestingEmployeeId = UUID.fromString(employeeId),
                        limit = limit,
                        offset = offset,
                        active = active,
                        actorCapabilities = call.heldCapabilities(),
                    )
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Create an employee.
             *
             * Description: Requires MANAGER or ADMIN role. The username and employee number must be unique. The creating employee is taken from the JWT and recorded.
             *
             * Tag: employees
             * Response: 400 The request body failed validation.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller is not a MANAGER or ADMIN, or the requested role's group confers a capability the caller does not hold (a manager cannot create an administrator).
             * Response: 409 That username or employee number is already taken.
             */
            post {
                val claim = call.getTokenClaims() ?: throw invalidCredentials()

                val employeeRequest = call.receive<EmployeeDto.CreateEmployeeRequestDto>()
                logger.info("Creating new employee with username: ${employeeRequest.username}")

                val dto = employeeService.createEmployee(claim.employeeId, employeeRequest, call.heldCapabilities())
                call.respond(HttpStatusCode.Created, dto)
            }

            /**
             * Get an employee by ID.
             *
             * Description: Self or manager — an employee may always read their own record, but reading anyone else's requires MANAGER role or higher.
             *
             * Tag: employees
             * Path: id Employee identifier, a UUID.
             * Response: 400 id is missing, or is not a valid UUID.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller is reading another employee and is not a MANAGER.
             * Response: 404 No employee with that id.
             */
            get("/{id}") {
                val claims = call.getTokenClaims() ?: throw invalidCredentials()

                val employeeIdParam =
                    call.parameters["id"]
                        ?: throw validationFailed("Employee ID is required")

                if (!call.isSelfOrHolds(employeeIdParam, Capability.EMPLOYEE_READ)) {
                    throw PosException(
                        PosErrorCode.AUTH_FORBIDDEN,
                        OWNERSHIP_DENIED_STATUS,
                        "Reading another employee's record requires EMPLOYEE_READ",
                    )
                }

                val employeeId = parseUuid(employeeIdParam, "id")
                val dto = employeeService.getEmployeeById(employeeId)
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Update an employee.
             *
             * Description: Self or manager — an employee may edit their own record, but editing anyone else's requires MANAGER role or higher. Only the fields present in the body are changed.
             *
             * Tag: employees
             * Path: id Employee identifier, a UUID.
             * Response: 400 id is missing or not a valid UUID, or the body failed validation.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller is editing another employee and is not a MANAGER, or changes an administrative field (`trainingMode`, `employmentStatus`, `notes`, `personnelNumber`, `defaultCostCenterId`) without EMPLOYEE_WRITE — also on one's own record, where only `pin` and `languagePreference` are self-service. Sending a field's current value is not a change.
             * Response: 404 No employee with that id.
             * Response: 409 That username or employee number is already taken.
             */
            put("/{id}") {
                val claims = call.getTokenClaims() ?: throw invalidCredentials()

                val employeeIdParam =
                    call.parameters["id"]
                        ?: throw validationFailed("Employee ID is required")

                if (!call.isSelfOrHolds(employeeIdParam, Capability.EMPLOYEE_WRITE)) {
                    throw PosException(
                        PosErrorCode.AUTH_FORBIDDEN,
                        OWNERSHIP_DENIED_STATUS,
                        "Editing another employee's record requires EMPLOYEE_WRITE",
                    )
                }

                val employeeId = parseUuid(employeeIdParam, "id")
                val updateRequest = call.receive<EmployeeDto.UpdateEmployeeRequestDto>()
                logger.info("Updating employee with ID: $employeeId")

                val dto = employeeService.updateEmployee(employeeId, updateRequest, claims.employeeId, call.heldCapabilities())
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Deactivate an employee.
             *
             * Description: Requires MANAGER role. Despite the verb, nothing is erased — the employee is flagged inactive so their past sales, tips and audit entries stay attributable. A deactivated employee can no longer log in.
             *
             * Tag: employees
             * Path: id Employee identifier, a UUID.
             * Response: 400 id is missing, or is not a valid UUID.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No employee with that id.
             */
            delete("/{id}") {
                val employeeIdParam =
                    call.parameters["id"]
                        ?: throw validationFailed("Employee ID is required")
                val employeeId = parseUuid(employeeIdParam, "id")
                val userId = call.getEmployeeIdFromJwt()
                logger.info("Deleting employee with ID: $employeeId")

                employeeService.deleteEmployee(employeeId, userId)
                call.respond(HttpStatusCode.OK, "Employee deactivated successfully")
            }

            /**
             * Deactivate an employee explicitly.
             *
             * Description: Requires MANAGER role. Same effect as the delete route, under a verb that says what actually happens — the employee is flagged inactive and can no longer log in.
             *
             * Tag: employees
             * Path: id Employee identifier, a UUID.
             * Response: 400 id is missing, or is not a valid UUID.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No employee with that id.
             */
            patch("/{id}/deactivate") {
                val employeeIdParam =
                    call.parameters["id"]
                        ?: throw validationFailed("Employee ID is required")
                val employeeId = parseUuid(employeeIdParam, "id")
                logger.info("Deactivating employee with ID: $employeeId")

                employeeService.deactivateEmployee(employeeId, call.getEmployeeIdFromJwt())
                call.respond(HttpStatusCode.OK, "Employee deactivated successfully")
            }

            /**
             * Set an employee's rotation-pool membership.
             *
             * Description: Requires MANAGER or ADMIN role, checked both centrally and inline here. The rotation pool decides who is dealt new tables automatically.
             *
             * Tag: employees
             * Path: id Employee identifier, a UUID.
             * Response: 400 id is missing, is not a valid UUID, or the employee is a terminal account being added to the pool.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller lacks EMPLOYEE_WRITE.
             * Response: 404 No employee with that id.
             */
            patch("/{id}/rotation-pool") {
                call.getTokenClaims() ?: throw invalidCredentials()

                val employeeIdParam =
                    call.parameters["id"]
                        ?: throw validationFailed("Employee ID is required")
                val employeeId = parseUuid(employeeIdParam, "id")

                val request = call.receive<EmployeeDto.RotationPoolUpdateRequestDto>()
                logger.info("Setting rotation pool for employee {} to {}", employeeId, request.inRotationPool)

                val updated = employeeService.setRotationPool(employeeId, request.inRotationPool)
                call.respond(HttpStatusCode.OK, updated)
            }

            /**
             * Assign an employee to an existing permission group.
             *
             * Description: The group decides what the employee may reach; `role` survives only for the JWT claim and client display. Requires PERMISSION_ADMIN. You cannot change your own group, and you cannot confer capabilities you do not hold yourself. This does not create or edit groups.
             *
             * Tag: employees
             * Path: id Employee identifier, a UUID.
             * Response: 400 id is missing or not a valid UUID, or the group code matches no seeded group.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller is assigning their own group, or the group confers something the caller does not hold.
             * Response: 404 No employee with that id.
             */
            put("/{id}/permission-group") {
                val claims = call.getTokenClaims() ?: throw invalidCredentials()
                val actorId =
                    claims.employeeId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: throw invalidCredentials()

                val employeeIdParam =
                    call.parameters["id"]
                        ?: throw validationFailed("Employee ID is required")
                val employeeId = parseUuid(employeeIdParam, "id")

                val request = call.receive<EmployeeDto.AssignPermissionGroupRequestDto>()
                val assignment = employeeService.assignPermissionGroup(employeeId, request.groupCode, actorId)
                call.respond(HttpStatusCode.OK, assignment)
            }

            /**
             * Change an employee's password.
             *
             * Description: Self or manager. Changing your own password requires the current one in the body; a manager changing someone else's does not. The new password is validated against the password policy.
             *
             * Tag: employees
             * Path: id Employee identifier, a UUID.
             * Response: 400 id is missing or not a valid UUID, or the new password failed validation.
             * Response: 401 Missing or invalid bearer token, or the current password is wrong.
             * Response: 403 Caller is changing another employee's password and is not a MANAGER.
             * Response: 404 No employee with that id.
             */
            patch("/{id}/password") {
                val claims = call.getTokenClaims() ?: throw invalidCredentials()

                val employeeIdParam =
                    call.parameters["id"]
                        ?: throw validationFailed("Employee ID is required")

                if (!call.isSelfOrHolds(employeeIdParam, Capability.EMPLOYEE_CREDENTIAL_RESET)) {
                    throw PosException(
                        PosErrorCode.AUTH_FORBIDDEN,
                        OWNERSHIP_DENIED_STATUS,
                        "Changing another employee's password requires EMPLOYEE_CREDENTIAL_RESET",
                    )
                }

                val employeeId = parseUuid(employeeIdParam, "id")
                val request = call.receive<EmployeeDto.PasswordChangeRequestDto>()
                try {
                    request.validate()
                } catch (e: IllegalArgumentException) {
                    throw validationFailed(e.message ?: "Invalid password change request")
                }

                logger.info("Password change request for employee ID: $employeeId by ${claims.employeeId}")
                employeeService.changePassword(employeeId, request.currentPassword, request.newPassword)
                call.respond(HttpStatusCode.OK, "Password changed successfully")
            }

            /**
             * Change my language preference.
             *
             * Description: Self-service — the employee comes from the JWT, so a caller can only change their own. The new locale is passed in the `languageCode` query parameter and drives the language of receipts printed by this employee.
             *
             * Tag: employees
             * Response: 400 The languageCode query parameter is missing.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 404 The token references an employee that no longer exists.
             */
            patch("/language") {
                val claim = call.getTokenClaims() ?: throw invalidCredentials()
                val languageCode =
                    call.request.queryParameters["languageCode"]
                        ?: throw validationFailed("Language code is required")

                val employeeId = UUID.fromString(claim.employeeId)
                logger.info("Updating language preference for employee ID: $employeeId to $languageCode")

                employeeService.updateEmployeeLanguage(employeeId, languageCode)
                call.respond(HttpStatusCode.OK, "Language preference updated successfully")
            }

            /**
             * Change my PIN.
             *
             * Description: Self-service — the employee comes from the JWT. The new PIN is passed in the `pin` query parameter and must be 4 to 6 digits. The PIN is what authorises manager overrides at another cashier's device.
             *
             * Tag: employees
             * Response: 400 The pin query parameter is missing, or is not 4 to 6 digits.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 404 The token references an employee that no longer exists.
             */
            patch("/me/pin") {
                val claims = call.getTokenClaims() ?: throw invalidCredentials()
                val employeeId = UUID.fromString(claims.employeeId)
                val newPin =
                    call.request.queryParameters["pin"]
                        ?: throw validationFailed("PIN is required")

                if (newPin.length < 4 || newPin.length > 6 || !newPin.all { it.isDigit() }) {
                    throw validationFailed("PIN must be 4-6 digits")
                }

                logger.info("Self-service PIN change for employee ID: $employeeId")
                employeeService.changeMyPin(employeeId, newPin)
                call.respond(HttpStatusCode.OK, "PIN changed successfully")
            }

            /**
             * Verify an employee's credentials.
             *
             * Description: Checks a username and password and returns the matching employee, without issuing any token — this is the credential check behind in-app confirmation prompts, not the login route.
             *
             * Tag: employees
             * Response: 400 The request body failed validation.
             * Response: 401 Missing or invalid bearer token, or the username and password do not match.
             */
            post("/authenticate") {
                val authRequest = call.receive<EmployeeDto.AuthenticationRequestDto>()
                try {
                    authRequest.validate()
                } catch (e: IllegalArgumentException) {
                    throw validationFailed(e.message ?: "Invalid authentication request")
                }

                val authenticated =
                    employeeService.authenticateEmployee(
                        authRequest.username,
                        authRequest.password,
                    )

                if (authenticated != null) {
                    call.respond(HttpStatusCode.OK, authenticated)
                } else {
                    throw invalidCredentials("Invalid username or password")
                }
            }

            /**
             * Reset another employee's password.
             *
             * Description: Requires MANAGER or ADMIN role, and only an ADMIN may reset an ADMIN's credentials. No current password is needed — this is the forgotten-password path, used when a manager stands at the device.
             *
             * Tag: employees
             * Path: id Employee identifier, a UUID.
             * Response: 400 id is missing or not a valid UUID, or the new password failed validation.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller is not a MANAGER, or is targeting an ADMIN without being one.
             * Response: 404 No employee with that id.
             */
            post("/{id}/reset-password") {
                val claims = call.getTokenClaims() ?: throw invalidCredentials()
                val actingRole = EmployeeRole.getRole(claims.role)
                if (!actingRole.isManagerOrHigher()) {
                    throw PosException(
                        PosErrorCode.AUTH_FORBIDDEN,
                        HttpStatusCode.Forbidden,
                        "Only managers or higher can reset credentials",
                    )
                }

                val employeeId =
                    parseUuid(
                        call.parameters["id"] ?: throw validationFailed("Employee ID is required"),
                        "id",
                    )
                val target = employeeService.getEmployeeById(employeeId)
                if (EmployeeRole.getRole(target.role) == EmployeeRole.ADMIN && actingRole != EmployeeRole.ADMIN) {
                    throw PosException(
                        PosErrorCode.AUTH_FORBIDDEN,
                        HttpStatusCode.Forbidden,
                        "Only an admin can reset an admin's credentials",
                    )
                }

                val request = call.receive<EmployeeDto.AdminResetPasswordRequestDto>()
                try {
                    request.validate()
                } catch (e: IllegalArgumentException) {
                    throw validationFailed(e.message ?: "Invalid reset request")
                }

                logger.info("Password reset for employee {} by {}", employeeId, claims.employeeId)
                employeeService.resetPassword(employeeId, request.newPassword)
                call.respond(HttpStatusCode.OK, "Password reset successfully")
            }

            /**
             * Reset another employee's PIN.
             *
             * Description: Requires MANAGER or ADMIN role, and only an ADMIN may reset an ADMIN's credentials. The counterpart of the password reset, for the PIN used at the register.
             *
             * Tag: employees
             * Path: id Employee identifier, a UUID.
             * Response: 400 id is missing or not a valid UUID, or the new PIN failed validation.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller is not a MANAGER, or is targeting an ADMIN without being one.
             * Response: 404 No employee with that id.
             */
            post("/{id}/reset-pin") {
                val claims = call.getTokenClaims() ?: throw invalidCredentials()
                val actingRole = EmployeeRole.getRole(claims.role)
                if (!actingRole.isManagerOrHigher()) {
                    throw PosException(
                        PosErrorCode.AUTH_FORBIDDEN,
                        HttpStatusCode.Forbidden,
                        "Only managers or higher can reset credentials",
                    )
                }

                val employeeId =
                    parseUuid(
                        call.parameters["id"] ?: throw validationFailed("Employee ID is required"),
                        "id",
                    )
                val target = employeeService.getEmployeeById(employeeId)
                if (EmployeeRole.getRole(target.role) == EmployeeRole.ADMIN && actingRole != EmployeeRole.ADMIN) {
                    throw PosException(
                        PosErrorCode.AUTH_FORBIDDEN,
                        HttpStatusCode.Forbidden,
                        "Only an admin can reset an admin's credentials",
                    )
                }

                val request = call.receive<EmployeeDto.AdminResetPinRequestDto>()
                try {
                    request.validate()
                } catch (e: IllegalArgumentException) {
                    throw validationFailed(e.message ?: "Invalid reset request")
                }

                logger.info("PIN reset for employee {} by {}", employeeId, claims.employeeId)
                employeeService.resetPin(employeeId, request.pin)
                call.respond(HttpStatusCode.OK, "PIN reset successfully")
            }
        }
    }
}

private fun invalidCredentials(detail: String = "Invalid authentication token") =
    PosException(
        PosErrorCode.AUTH_INVALID_CREDENTIALS,
        HttpStatusCode.Unauthorized,
        detail,
    )

private fun validationFailed(msg: String) =
    PosException(
        PosErrorCode.VALIDATION_FAILED,
        HttpStatusCode.BadRequest,
        msg,
    )

private fun parseUuid(
    value: String,
    field: String,
): UUID =
    try {
        UUID.fromString(value)
    } catch (e: IllegalArgumentException) {
        throw validationFailed("$field must be a valid UUID")
    }
