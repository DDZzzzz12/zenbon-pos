package org.example.project.admin.core.auth

import org.example.project.core.auth.AccessPolicy
import org.example.project.core.domain.enums.auth.Capability

/**
 * Whether the principal may reset another employee's PIN or password.
 *
 * `POST /api/employees/{id}/reset-pin` and `/reset-password` are `EMPLOYEE_CREDENTIAL_RESET` in
 * `EmployeeRules` — deliberately not `EMPLOYEE_WRITE`, so a group may edit staff records without
 * being able to take over their logins. The staff screen hides the two buttons otherwise
 * (CLAUDE.md rule 8).
 */
fun canResetStaffCredentials(policy: AccessPolicy): Boolean = policy.allows(Capability.EMPLOYEE_CREDENTIAL_RESET)
