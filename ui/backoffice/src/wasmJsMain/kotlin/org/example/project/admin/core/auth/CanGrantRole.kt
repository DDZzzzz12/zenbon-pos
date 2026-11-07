package org.example.project.admin.core.auth

import org.example.project.core.auth.AccessPolicy
import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.domain.enums.employee.EmployeeRole

/**
 * Whether the principal may put an employee into [role] — or take them out of it.
 *
 * A role change moves the employee into the role's permission group, so `EmployeeServiceImpl`
 * refuses (403) any role whose group confers a capability the actor does not hold: a manager can
 * neither create an administrator nor promote or demote one (QA admin19 NOTE-i). The staff editor
 * hides such roles so it never offers what the server will refuse (CLAUDE.md rule 8).
 *
 * Reads the compiled bundle rather than the group row: a role's group is a seeded system group,
 * whose capabilities are fixed and pinned to [CapabilityBundles] by `SeededGroupsMatchBundlesTest`.
 */
fun canGrantRole(
    policy: AccessPolicy,
    role: EmployeeRole,
): Boolean = CapabilityBundles.forRole(role).all { policy.allows(it) }

/** [canGrantRole] for a role as the API spells it (`EmployeeSummaryResponseDto.role`). */
fun canGrantRole(
    policy: AccessPolicy,
    roleName: String,
): Boolean = canGrantRole(policy, EmployeeRole.getRole(roleName))
