package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * The back-office administration surface — route-permission introspection and the permission-group
 * editor.
 *
 * Everything here is gated on `PERMISSION_ADMIN` and nothing else. Reading the effective rule table
 * is itself sensitive, because it maps the whole attack surface; authoring the groups that table is
 * evaluated against is more sensitive still.
 *
 * `PERMISSION_ADMIN` is `Assignability.RESERVED`, so no group edit can confer it. That is what keeps
 * "may manage groups" from being a route to acquiring it — and it is deliberately not
 * `EMPLOYEE_WRITE`: a manager who may edit staff records must not thereby be able to author what
 * staff may do.
 */
object AdminRules : RuleModule {
    override val name = "Admin"

    override val pathPrefixes = listOf("/api/admin")

    override val allowedCapabilities = setOf(Capability.PERMISSION_ADMIN)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/admin/permissions" to HttpMethod.Get) to Cap(Capability.PERMISSION_ADMIN),
            ("/api/admin/permissions/effective" to HttpMethod.Get) to Cap(Capability.PERMISSION_ADMIN),
            ("/api/admin/permissions/explain" to HttpMethod.Get) to Cap(Capability.PERMISSION_ADMIN),
            ("/api/admin/permission-groups" to HttpMethod.Get) to Cap(Capability.PERMISSION_ADMIN),
            ("/api/admin/permission-groups" to HttpMethod.Post) to Cap(Capability.PERMISSION_ADMIN),
            ("/api/admin/permission-groups/capabilities" to HttpMethod.Get) to Cap(Capability.PERMISSION_ADMIN),
            ("/api/admin/permission-groups/{code}" to HttpMethod.Get) to Cap(Capability.PERMISSION_ADMIN),
            ("/api/admin/permission-groups/{code}" to HttpMethod.Put) to Cap(Capability.PERMISSION_ADMIN),
            ("/api/admin/permission-groups/{code}" to HttpMethod.Delete) to Cap(Capability.PERMISSION_ADMIN),
        )
}
