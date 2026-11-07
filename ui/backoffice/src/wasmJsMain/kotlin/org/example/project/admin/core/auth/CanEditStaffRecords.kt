package org.example.project.admin.core.auth

import org.example.project.core.auth.AccessPolicy
import org.example.project.core.domain.enums.auth.Capability

/**
 * Whether the principal may change an employee's administrative fields — training mode, notes,
 * personnel number (and, server-side, employment status and default cost centre).
 *
 * `EMPLOYEE_WRITE`, also on one's own record: `PUT /api/employees/{id}` lets anyone edit their own
 * row, but `EmployeeServiceImpl` refuses a change to any of these without the capability (SET-01).
 * The staff editor shows them read-only otherwise, so the form never offers what the server will
 * refuse (CLAUDE.md rule 8).
 */
fun canEditStaffRecords(policy: AccessPolicy): Boolean = policy.allows(Capability.EMPLOYEE_WRITE)
