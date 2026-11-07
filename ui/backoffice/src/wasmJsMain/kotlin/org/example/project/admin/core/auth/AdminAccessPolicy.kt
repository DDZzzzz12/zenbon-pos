package org.example.project.admin.core.auth

import org.example.project.core.auth.AccessPolicy
import org.example.project.core.auth.TokenStore
import org.example.project.core.auth.resolveAccessPolicy
import org.example.project.core.auth.toCapabilitiesOrNull
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole

/**
 * What the current back-office principal may do, per the server.
 *
 * adminApp has no `PermissionManager` — its session is just the [TokenStore] — so this is the
 * equivalent seam. Both clients resolve through the SAME `resolveAccessPolicy` in `shared/`,
 * because two copies of that rule is exactly how the client and server hierarchies drifted apart in
 * defect D2.
 *
 * `temporary` is always null: step-up is a till mechanism, and nobody elevates in the back office.
 *
 * Not Compose state, matching the helpers it replaces — but that is only safe because callers key
 * their memos on `SessionState.capabilityEpoch`.
 *
 * An earlier version of this note claimed login and token refresh "both already flip
 * `SessionState.status`, which recomposes the tree". That is false for a refresh: `PosHttpClient`
 * calls `onUnauthorized` only when the refresh FAILS, so a successful one moves no Compose state.
 * The claim was inherited from the role-based helpers, where it was near enough because a role
 * rarely changes; capabilities change precisely because they are refreshed, and two memos keyed on
 * the singleton token store went stale behind that wrong claim.
 */
val TokenStore.accessPolicy: AccessPolicy
    get() =
        resolveAccessPolicy(
            reported = capabilities.toCapabilitiesOrNull(),
            temporary = null,
            effectiveRole = role?.let { name -> EmployeeRole.entries.firstOrNull { it.name == name } },
        )

/**
 * Whether the current principal holds [capability].
 *
 * The one question every adminApp gate should ask. Where the server reported no capabilities — an
 * older server — this falls back to the role, so an un-upgraded server keeps working rather than
 * presenting an empty console; see `resolveAccessPolicy`.
 */
fun TokenStore.holds(capability: Capability): Boolean = accessPolicy.allows(capability)
