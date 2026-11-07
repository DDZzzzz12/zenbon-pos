package org.example.project.infrastructure.plugins

import org.example.project.core.domain.enums.employee.EmployeeRole

/**
 * The pure "does this role satisfy this requirement" predicate.
 *
 * **Nothing gates a route on a role any more.** The whole `ApplicationCall.isManagerOrHigher()` /
 * `hasRole` / `hasAnyRole` / `hasPermission` / `requirePermissionOrRole` family that used to live
 * here is gone, along with its counterpart in `server-common`'s `AuthUtils`. Every route is gated by
 * `RoutePermissions` on a capability, and `RoutePermissionsCoverageTest` proves no route escapes it.
 *
 * Why they were deleted rather than left for convenience:
 *
 * 1. **Ten of them checked nothing.** `call.isManagerOrHigher()` was written as a bare statement in
 *    `ZBonRoutes` and `DsfinvkExportRoutes` — the boolean was discarded. Three more in
 *    `LocationTypeRoutes` returned without responding at all.
 * 2. **The rest contradicted the group model.** Role and group legitimately disagree since Stage 3:
 *    an AUDITOR holds `ZBON_MANAGE` but may carry role `CASHIER`, so a surviving role gate would
 *    have refused them at a route their group admits. That is defect D2 in a new place.
 * 3. **Two copies had already drifted.** `server-common`'s `hasAnyRole` never received the ADMIN
 *    escape that Stage 1 added here (defect D3) — the fix landed on one copy and not the other,
 *    which is precisely the trap a duplicated authorization helper sets.
 *
 * [roleSatisfies] survives because ADMIN-escape semantics are still worth stating once and testing
 * (`RoleSatisfiesTest`), and because the handful of remaining role comparisons — "may this actor
 * reset THAT employee's password", the rotation pool, the notification fan-out — are relationship
 * rules the registry cannot express and are not route gates.
 *
 * Fails closed on an empty requirement: a set assembled dynamically that comes out empty must not
 * open the route to everyone.
 */
fun roleSatisfies(
    role: EmployeeRole,
    required: Set<EmployeeRole>,
): Boolean = role == EmployeeRole.ADMIN || role in required
