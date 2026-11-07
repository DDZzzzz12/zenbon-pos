package org.example.project.infrastructure.security

import org.example.project.core.domain.enums.employee.EmployeeRole

/**
 * The pre-migration `AccessLevel`, preserved as its own type for [LegacyRuleFixture].
 *
 * Test-only. It deliberately does **not** reuse the production `AccessLevel`: that type is still
 * evolving — Stage 1 deleted `Role`, `AnyRole`, `PermissionOrRole` and `Authenticated` from it, and
 * Stage 1c adds `CapWithOwnership` — while the frozen baseline must keep describing the model as it
 * was on 2026-08-21 no matter what production does next.
 *
 * Coupling the two would mean every future change to the production sealed class either breaks the
 * baseline or, worse, silently redefines what the equivalence test is comparing against.
 */
sealed class LegacyAccessLevel {
    data object Public : LegacyAccessLevel()

    data object Authenticated : LegacyAccessLevel()

    data class Role(
        val role: EmployeeRole,
    ) : LegacyAccessLevel()

    data class AnyRole(
        val roles: Set<EmployeeRole>,
    ) : LegacyAccessLevel()

    data class PermissionOrRole(
        val permission: LegacyPermission,
        val role: EmployeeRole,
    ) : LegacyAccessLevel()

    data class Custom(
        val description: String,
    ) : LegacyAccessLevel()
}
