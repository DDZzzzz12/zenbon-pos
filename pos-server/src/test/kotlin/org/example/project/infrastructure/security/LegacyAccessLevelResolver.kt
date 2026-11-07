package org.example.project.infrastructure.security

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.employee.EmployeeRole

/**
 * The pre-migration authorization algorithm, lifted from `RouteAuthorizationPlugin` as of Stage 1.
 *
 * Test-only; never shipped. It exists solely so [CapabilityEquivalenceTest] has a reference verdict
 * to compare the capability resolver against, and it reads [LegacyRuleFixture] rather than the live
 * registry so it keeps describing the past as the registry moves.
 *
 * **Scope: the role/permission dimension only.** The plugin also enforces a terminal-capability
 * ceiling on the routes in `TransactionalRoutes` before this logic runs, but that gate is keyed on
 * device identity, not role, and Stage 1 does not touch it. Modelling it here would add a dimension
 * the migration cannot change.
 */
object LegacyAccessLevelResolver {
    /**
     * Whether [role] holding [permissions] could reach `method path` before the migration.
     *
     * An unregistered route resolves to `false`: the registry denies by default, which is the
     * property the coverage test protects.
     */
    fun verdict(
        path: String,
        method: HttpMethod,
        role: EmployeeRole,
        permissions: Set<LegacyPermission>,
    ): Boolean =
        when (val rule = findLegacyRule(path, method)) {
            null -> false
            is LegacyAccessLevel.Public -> true
            is LegacyAccessLevel.Authenticated -> true
            // The plugin skips enforcement entirely and defers to the route handler, so from the
            // registry's point of view the call is allowed through. Stage 1c changes this.
            is LegacyAccessLevel.Custom -> true
            is LegacyAccessLevel.Role -> role == EmployeeRole.ADMIN || role == rule.role
            is LegacyAccessLevel.AnyRole -> role == EmployeeRole.ADMIN || role in rule.roles
            is LegacyAccessLevel.PermissionOrRole ->
                role == EmployeeRole.ADMIN || rule.permission in permissions || role == rule.role
        }

    /** Mirrors `RoutePermissions.findRule`: O(1) exact match first, then parameterized patterns. */
    private fun findLegacyRule(
        path: String,
        method: HttpMethod,
    ): LegacyAccessLevel? {
        LegacyRuleFixture.rules[path to method]?.let { return it }
        return LegacyRuleFixture.rules.entries
            .firstOrNull { (key, _) ->
                key.first.contains("{") &&
                    ParameterizedRule(key.first, key.second, AccessLevel.Public)
                        .matches(path, method)
            }?.value
    }
}
