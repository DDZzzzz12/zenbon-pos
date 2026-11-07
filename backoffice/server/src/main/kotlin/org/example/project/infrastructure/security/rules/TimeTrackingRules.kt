package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.AccessLevel.CapWithOwnership
import org.example.project.infrastructure.security.RuleModule

/**
 * Clocking in and out, breaks, approvals and the payroll export — `TimeTrackingRoutes.kt`,
 * `TimeApprovalRoutes.kt`, `DatevExportRoutes.kt`.
 *
 * The default here is `SELF_*`, not `TIMETRACKING_*`: almost everything an employee does in this
 * feature concerns their OWN timesheet — clocking in, taking a break, correcting an entry,
 * submitting it for approval, printing their own PDF. Those were all `Authenticated` and stay
 * reachable by every role.
 *
 * Only what touches SOMEONE ELSE is `TIMETRACKING_*`: the team view, the pending-approval queue,
 * approving or rejecting, and adding a manual entry. `GET /all` is `Role(ADMIN)` and therefore
 * cannot share the manager-level team capability — hence `TIMETRACKING_ALL_READ`.
 *
 * The two per-employee reads are `CapWithOwnership` since Stage 1c. `TIMETRACKING_READ` is the
 * right second capability rather than `TIMETRACKING_ALL_READ`: reading a colleague's timesheet is
 * the team view, which is manager-level, while `_ALL_READ` is the admin-only `GET /all`.
 *
 * **Neither handler checked anything before Stage 1c**, despite the registry describing them as
 * "self or manager" and the overview's own comment saying "manager only". That is what a `Custom`
 * arm cost: the registry deferred, and the handler never picked it up.
 *
 * Latent rather than live — the feature has been de-integrated since 2026-05-07 (`Routing.kt` does
 * not call `timeTrackingRoutes()`), so nobody could reach them. Fixing the handlers now is what
 * stops the hole shipping with the redesign; `OwnershipFloorTest` carries the `@Disabled` tests that
 * prove it, ready to run when the routes are re-mounted.
 */
object TimeTrackingRules : RuleModule {
    override val name = "Time tracking"

    override val pathPrefixes = listOf("/api/timetracking")

    override val allowedCapabilities =
        setOf(
            Capability.SELF_READ,
            Capability.SELF_WRITE,
            Capability.TIMETRACKING_ALL_READ,
            Capability.TIMETRACKING_DATEV_EXPORT,
            Capability.TIMETRACKING_READ,
            Capability.TIMETRACKING_WRITE,
        )

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/timetracking/clock-in" to HttpMethod.Post) to Cap(Capability.SELF_WRITE),
            ("/api/timetracking/clock-out" to HttpMethod.Post) to Cap(Capability.SELF_WRITE),
            ("/api/timetracking/break/start" to HttpMethod.Post) to Cap(Capability.SELF_WRITE),
            ("/api/timetracking/break/end" to HttpMethod.Post) to Cap(Capability.SELF_WRITE),
            ("/api/timetracking/active" to HttpMethod.Get) to Cap(Capability.SELF_READ),
            ("/api/timetracking/current" to HttpMethod.Get) to Cap(Capability.SELF_READ),
            ("/api/timetracking/my" to HttpMethod.Get) to Cap(Capability.SELF_READ),
            ("/api/timetracking/overview" to HttpMethod.Get) to Cap(Capability.SELF_READ),
            ("/api/timetracking/manual" to HttpMethod.Post) to Cap(Capability.TIMETRACKING_WRITE),
            ("/api/timetracking/{id}" to HttpMethod.Put) to Cap(Capability.SELF_WRITE),
            ("/api/timetracking/{id}" to HttpMethod.Delete) to Cap(Capability.SELF_WRITE),
            ("/api/timetracking/team" to HttpMethod.Get) to Cap(Capability.TIMETRACKING_READ),
            ("/api/timetracking/all" to HttpMethod.Get) to Cap(Capability.TIMETRACKING_ALL_READ),
            ("/api/timetracking/my-entries-auth" to HttpMethod.Post) to Cap(Capability.SELF_READ),
            ("/api/timetracking/employee/{employeeId}" to HttpMethod.Get) to
                CapWithOwnership(
                    setOf(Capability.SELF_READ, Capability.TIMETRACKING_READ),
                    "own timesheet, else TIMETRACKING_READ",
                ),
            ("/api/timetracking/employee/{employeeId}/overview" to HttpMethod.Get) to
                CapWithOwnership(
                    setOf(Capability.SELF_READ, Capability.TIMETRACKING_READ),
                    "own timesheet, else TIMETRACKING_READ",
                ),
            ("/api/timetracking/approval/submit" to HttpMethod.Post) to Cap(Capability.SELF_WRITE),
            ("/api/timetracking/approval/{id}/approve" to HttpMethod.Post) to Cap(Capability.TIMETRACKING_WRITE),
            ("/api/timetracking/approval/{id}/reject" to HttpMethod.Post) to Cap(Capability.TIMETRACKING_WRITE),
            ("/api/timetracking/approval/pending" to HttpMethod.Get) to Cap(Capability.TIMETRACKING_READ),
            ("/api/timetracking/approval/my-status" to HttpMethod.Get) to Cap(Capability.SELF_READ),
            ("/api/timetracking/datev/preview" to HttpMethod.Get) to Cap(Capability.TIMETRACKING_DATEV_EXPORT),
            ("/api/timetracking/datev/export" to HttpMethod.Get) to Cap(Capability.TIMETRACKING_DATEV_EXPORT),
            ("/api/timetracking/datev/pdf" to HttpMethod.Get) to Cap(Capability.SELF_READ),
        )
}
