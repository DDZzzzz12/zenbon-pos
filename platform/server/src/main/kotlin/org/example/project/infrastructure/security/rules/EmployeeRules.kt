package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.AccessLevel.CapWithOwnership
import org.example.project.infrastructure.security.RuleModule

/**
 * Employee records, HR info, schedules and credentials — `EmployeeRoutes.kt`,
 * `EmployeeInfoRoutes.kt`.
 *
 * The `SELF_*` split does the work here. Anything an employee does to their OWN record —
 * reading their profile, changing their language or PIN, re-authenticating, editing their own
 * HR info — carries `SELF_READ`/`SELF_WRITE`, which every role holds. Anything touching ANOTHER
 * employee is `EMPLOYEE_READ`/`EMPLOYEE_WRITE`, and resetting someone else's credentials is the
 * separate `EMPLOYEE_CREDENTIAL_RESET`.
 *
 * `/api/employees/info/active` is the shift roster the till shows, so it stays `SELF_READ`
 * rather than `EMPLOYEE_READ` — it was `Authenticated` and every till needs it.
 *
 * The four per-employee rules are `CapWithOwnership` since Stage 1c: the plugin enforces a floor —
 * `SELF_*` for your own row, the manager-level capability for anyone else's — and the handler proves
 * which case applies. `GET /{employeeId}/schedule` is the one that needed a handler fix as well as a
 * rule change: its KDoc promised "403 Caller is reading another employee's schedule and is not a
 * MANAGER" and the handler checked nothing at all.
 */
object EmployeeRules : RuleModule {
    override val name = "Employees"

    override val pathPrefixes = listOf("/api/employees", "/api/employee")

    override val allowedCapabilities =
        setOf(
            Capability.EMPLOYEE_CREDENTIAL_RESET,
            Capability.EMPLOYEE_READ,
            Capability.EMPLOYEE_WRITE,
            Capability.PERMISSION_ADMIN,
            Capability.SELF_READ,
            Capability.SELF_WRITE,
        )

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/employees/me" to HttpMethod.Get) to Cap(Capability.SELF_READ),
            ("/api/employees/info/active" to HttpMethod.Get) to Cap(Capability.SELF_READ),
            ("/api/employees/summary" to HttpMethod.Get) to Cap(Capability.EMPLOYEE_READ),
            ("/api/employees" to HttpMethod.Post) to Cap(Capability.EMPLOYEE_WRITE),
            ("/api/employees/{id}" to HttpMethod.Get) to
                CapWithOwnership(
                    setOf(Capability.SELF_READ, Capability.EMPLOYEE_READ),
                    "own record, else EMPLOYEE_READ",
                ),
            ("/api/employees/{id}" to HttpMethod.Put) to
                CapWithOwnership(
                    setOf(Capability.SELF_WRITE, Capability.EMPLOYEE_WRITE),
                    "own record, else EMPLOYEE_WRITE",
                ),
            ("/api/employees/{id}" to HttpMethod.Delete) to Cap(Capability.EMPLOYEE_WRITE),
            ("/api/employees/{id}/deactivate" to HttpMethod.Patch) to Cap(Capability.EMPLOYEE_WRITE),
            ("/api/employees/{id}/rotation-pool" to HttpMethod.Patch) to Cap(Capability.EMPLOYEE_WRITE),
            ("/api/employees/{id}/password" to HttpMethod.Patch) to
                CapWithOwnership(
                    setOf(Capability.SELF_WRITE, Capability.EMPLOYEE_CREDENTIAL_RESET),
                    "own password, else EMPLOYEE_CREDENTIAL_RESET",
                ),
            ("/api/employees/language" to HttpMethod.Patch) to Cap(Capability.SELF_WRITE),
            ("/api/employees/me/pin" to HttpMethod.Patch) to Cap(Capability.SELF_WRITE),
            ("/api/employees/authenticate" to HttpMethod.Post) to Cap(Capability.SELF_READ),
            // PERMISSION_ADMIN is RESERVED, so only the ADMIN group holds it. Assigning an employee
            // to a group is deliberately NOT EMPLOYEE_WRITE: a manager who may edit staff records
            // must not thereby be able to hand out capabilities.
            ("/api/employees/{id}/permission-group" to HttpMethod.Put) to Cap(Capability.PERMISSION_ADMIN),
            ("/api/employees/{id}/reset-password" to HttpMethod.Post) to Cap(Capability.EMPLOYEE_CREDENTIAL_RESET),
            ("/api/employees/{id}/reset-pin" to HttpMethod.Post) to Cap(Capability.EMPLOYEE_CREDENTIAL_RESET),
            ("/api/employees/{employeeId}/schedule" to HttpMethod.Get) to
                CapWithOwnership(
                    setOf(Capability.SELF_READ, Capability.EMPLOYEE_READ),
                    "own schedule, else EMPLOYEE_READ",
                ),
            ("/api/employees/{employeeId}/schedule" to HttpMethod.Put) to Cap(Capability.EMPLOYEE_WRITE),
            ("/api/employees/{employeeId}/schedule/day" to HttpMethod.Put) to Cap(Capability.EMPLOYEE_WRITE),
            ("/api/employee/info/me" to HttpMethod.Get) to Cap(Capability.SELF_READ),
            ("/api/employee/info/me" to HttpMethod.Put) to Cap(Capability.SELF_WRITE),
            ("/api/employee/info" to HttpMethod.Post) to Cap(Capability.EMPLOYEE_WRITE),
            ("/api/employee/info/by-employee/{employeeId}" to HttpMethod.Get) to Cap(Capability.EMPLOYEE_READ),
            ("/api/employee/info/by-employee/{employeeId}" to HttpMethod.Put) to Cap(Capability.EMPLOYEE_WRITE),
            ("/api/employee/info/by-employee/{employeeId}" to HttpMethod.Delete) to Cap(Capability.EMPLOYEE_WRITE),
            ("/api/employee/info" to HttpMethod.Get) to Cap(Capability.EMPLOYEE_READ),
        )
}
