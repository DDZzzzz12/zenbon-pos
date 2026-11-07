/**
 * Utility for resolving the target employee used in data-filtering queries.
 *
 * Provides a shared method for determining which employee's data to return: the caller says
 * whether this principal may see everyone's rows, and this decides which rows that means.
 *
 * Used by services that need role-based data scoping (e.g., statistics, vouchers).
 *
 * Migrated to throws-style in Stage B Batch 10. Throws [PosException] on errors.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.utils

import io.ktor.http.HttpStatusCode
import org.example.project.core.error.PosErrorCode
import org.example.project.features.employee.domain.models.Employee
import org.example.project.infrastructure.errors.PosException
import java.util.UUID

/**
 * Which employee's rows a query should return: one of them, or all of them.
 *
 * Pure scoping. It holds **no authorization opinion of its own** — the caller answers
 * [seesEveryone] from the capability its own feature names, because "may see everyone's" is a
 * different capability per feature: `REPORT_ALL_READ` for statistics, `VOUCHER_REPORT` for the
 * shop-wide voucher rollups.
 *
 * Logic:
 * 1. [seesEveryone] with a filter — that employee's rows.
 * 2. [seesEveryone] without one — all rows (null).
 * 3. Otherwise — the requesting employee's own rows, whatever the filter said.
 *
 * ### Two things that used to be here and are gone
 *
 * **The role check.** This asked `role.isManagerOrHigher()`, so what an ACCOUNTANT or AUDITOR saw
 * depended on whichever `role` value happened to sit on their employee row — the field the group
 * model exists to stop deciding things.
 *
 * **The temporary-session branch**, which treated ANY active step-up as a manager and returned every
 * employee's rows. That was coherent while a session replaced the operator's identity with the
 * approver's. It stopped being coherent the moment step-up became a scoped union: a session lends
 * `ElevationScope.TILL_OVERRIDES` and nothing else, so a cashier who took a manager's PIN to void one
 * bill was silently also getting three minutes of everyone's sales figures.
 *
 * Removing the branch loses nothing, because [seesEveryone] is answered from `heldCapabilities()`,
 * which already includes anything a step-up lent. One path instead of two — and if a later
 * `ElevationScope` ever does lend a reporting capability, it flows through without another special
 * case.
 *
 * Must be invoked inside a `dbQuery` block since it performs Exposed lookups.
 *
 * @param employeeId Logged-in employee's ID from JWT
 * @param filterEmployeeId Optional employee ID filter, honoured only when [seesEveryone]
 * @param seesEveryone Whether this caller holds the capability that means "everyone's rows"
 * @return Target Employee (null = all employees)
 * @throws PosException if the requesting employee is not found, the filter UUID is invalid,
 *   or the filter target does not exist.
 */
fun resolveTargetEmployee(
    employeeId: String,
    filterEmployeeId: String?,
    seesEveryone: Boolean,
): Employee? {
    val employeeIdStr =
        employeeId.takeIf { it.isNotBlank() }
            ?: throw PosException(
                PosErrorCode.AUTH_INVALID_CREDENTIALS,
                HttpStatusCode.Unauthorized,
                "Missing employee id",
            )

    val requestingEmployeeUuid =
        try {
            UUID.fromString(employeeIdStr)
        } catch (e: IllegalArgumentException) {
            throw PosException(
                PosErrorCode.AUTH_INVALID_CREDENTIALS,
                HttpStatusCode.Unauthorized,
                "Invalid employee id",
            )
        }

    val requestingEmployee =
        Employee.findById(requestingEmployeeUuid)
            ?: throw employeeNotFound(requestingEmployeeUuid.toString())

    return when {
        filterEmployeeId != null && seesEveryone -> {
            val filterUuid = parseUuid(filterEmployeeId, "employee_id")
            Employee.findById(filterUuid) ?: throw employeeNotFound(filterEmployeeId)
        }
        seesEveryone -> null
        else -> requestingEmployee
    }
}

private fun parseUuid(
    value: String,
    field: String,
): UUID =
    try {
        UUID.fromString(value)
    } catch (e: IllegalArgumentException) {
        throw PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            "$field must be a valid UUID",
        )
    }

private fun employeeNotFound(id: String) =
    PosException(
        PosErrorCode.EMPLOYEE_NOT_FOUND,
        HttpStatusCode.NotFound,
        "Employee $id not found",
    )
