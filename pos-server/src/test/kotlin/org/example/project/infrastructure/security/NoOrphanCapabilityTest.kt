package org.example.project.infrastructure.security

import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.domain.enums.auth.Assignability
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The vocabulary must contain no dead values and no unreachable routes.
 *
 * A capability no route requires is a dead checkbox in Stage 5's admin UI: an operator ticks it,
 * believes access was granted, and gets a 403. A capability no role can hold is the mirror image —
 * a route nobody can reach, indistinguishable from a 404.
 */
@DisplayName("The capability vocabulary has no dead or unreachable values")
class NoOrphanCapabilityTest {
    private val required: Set<Capability> =
        RoutePermissions.modules
            .flatMap { it.rules.values }
            .flatMap { it.requiredCapabilities }
            .toSet()

    /**
     * Capabilities that deliberately gate no route **today**, each for a different reason. They are
     * listed individually rather than as a blanket exemption, because the reasons expire at
     * different times.
     */
    private val intentionallyUnattached =
        mapOf(
            // The landing place should Stage 3 revive EXCEPTION_EXPORT_DATA deliberately. That grant
            // is dead and forGrant returns null for it; attaching this to a real route would
            // re-open the hole where every grant holder reached the admin-only Debitoren export.
            Capability.DATEV_EXPORT to "target of a dead grant; must stay unattached",
            // Reserved for Stage 5's group-management API, which gates group CRUD on it. The 15
            // rules that carried it in Stage 1 did so only because they were
            // PermissionOrRole(EXCEPTION_SYSTEM_CONFIG, MANAGER) and a grant holder had to keep
            // reaching them. Stage 2a retired that mechanism, so they now carry the capability they
            // always meant — CONFIG_WRITE for config, CATALOG_MANAGE for the Pfand writes.
            Capability.SYSTEM_CONFIG to "reserved for the Stage 5 group API; gates no route yet",
            // Gates the step-up itself (POST /api/auth/temporary-session), which any authenticated
            // employee must be able to REACH — the caller is the cashier asking to be helped, not
            // the manager approving. The approver's PIN is checked inside the service, against this
            // capability. A route rule would gate the wrong person.
            Capability.ELEVATION_APPROVE to "checked against the APPROVER inside TemporarySessionServiceImpl, not the caller",
            // These gate no ROUTE and never will: they are the third gate kind (parent spec §2.1),
            // enforced in the service because the decision depends on the request BODY — which line
            // carries a custom price, how steep its discount is — not on the path.
            //
            // Stage 3 made them real. `OrderServiceImpl` used to compute one flag,
            // `role.isManagerOrHigher()`, for both checks; it now asks for `PRICE_OVERRIDE` and
            // `DISCOUNT_GRANT` by name. `PriceAuthorityEnforcementTest` asserts that enforcement
            // directly, so this exemption is no longer just a comment claiming something is fine.
            Capability.PRICE_OVERRIDE to "service-level gate in OrderServiceImpl; see PriceAuthorityEnforcementTest",
            // Also a handler-level gate rather than a route rule, and for a reason specific to it:
            // the ROUTE floor must stay ORDER_WRITE so a cashier can reach the defer path at all. A
            // floor of ORDER_ITEM_CANCEL would 403 them before they could ask a manager, which is
            // the opposite of what the approval flow is for. `requirePermissionOrDefer` then decides
            // whether the attempt executes now or waits — see StepUpElevationTest.
            Capability.ORDER_ITEM_CANCEL to "handler gate in OrderItemRoutes' defer path; route floor must stay ORDER_WRITE",
            Capability.CASHBOOK_BACKDATE to "handler gate in KassenbuchRoutes' defer path; route floor must stay CASHBOOK_WRITE",
            // Scopes the DATA behind /api/statistics/*, which every authenticated seller may read —
            // the route floor is REPORT_READ and has to stay there. This decides WHOSE rows come
            // back, and the `employee_id` filter that names them. A route rule cannot express
            // "same endpoint, narrower result"; see StatisticsScopingTest.
            Capability.REPORT_ALL_READ to "service-level scoping in resolveTargetEmployee; route floor must stay REPORT_READ",
            Capability.DISCOUNT_GRANT to "service-level gate in OrderServiceImpl; see PriceAuthorityEnforcementTest",
            // The POS-ERP cash-ledger integration was removed on `dev` (90cd23a92, Flyway V116)
            // while this arc was in flight, taking both routes these gated with it. The constants
            // survive on purpose, the same way OCR_LLM and the QUICK_SALE enums do: V110 and V112
            // already granted them to real permission groups, and a migration is never edited, so
            // deleting the constant would leave rows naming a capability the enum cannot parse.
            Capability.ERP_INTEGRATION_ADMIN to "gated the removed POS-ERP config routes; kept for rows written by V110",
            // Gated POST /transit-outbox/abandon, removed with the same integration. Kept as the
            // admin rung of the cash-book ladder for a future destructive action.
            Capability.CASHBOOK_ADMIN to "gated the removed transit-outbox abandon route; kept as the cash-book admin rung",
        )

    @Test
    fun `every capability is required by at least one route`() {
        val dead = Capability.entries.toSet() - required - intentionallyUnattached.keys
        assertTrue(
            dead.isEmpty(),
            "Capabilities no route requires — dead checkboxes in the Stage 5 admin UI: $dead",
        )
    }

    @Test
    fun `every non-reserved capability is held by a role other than ADMIN`() {
        // Deliberately excludes ADMIN. Its bundle is `Capability.entries.toSet()` by construction,
        // so including it would make this assertion unfailable — every capability is trivially
        // "grantable" and the unreachable-route condition it claims to detect could never fire.
        //
        // RESERVED capabilities are exempt because admin-only is exactly their point.
        val nonAdmin =
            EmployeeRole.entries
                .filterNot { it == EmployeeRole.ADMIN }
                .flatMap { CapabilityBundles.forRole(it) }
                .toSet()
        val adminOnly =
            Capability.entries
                .filterNot { it.assignability == Assignability.RESERVED }
                .filterNot { it in nonAdmin }
                .filterNot { it in intentionallyUnattached }
        assertEquals(
            expectedAdminOnly,
            adminOnly.toSet(),
            "Assignable capabilities reachable only by ADMIN. Each is a route no ordinary role can " +
                "reach; that may be correct, but it must be a listed decision rather than a silent one.",
        )
    }

    /**
     * Assignable capabilities that only ADMIN holds today, because every route requiring them was
     * `Role(ADMIN)` before the migration. Listed explicitly so the set cannot grow unnoticed.
     */
    private val expectedAdminOnly =
        setOf(
            Capability.BACKUP_CREATE,
            Capability.CARD_TERMINAL_MANAGE,
            Capability.DEBTOR_DATEV_EXPORT,
            // Holds the GitHub token problem reports are filed with and chooses where they go; a
            // credential, so administrator-only. MANAGER holds ISSUE_REPORT_CREATE to file.
            Capability.ISSUE_REPORT_MANAGE,
            Capability.PAYROLL_WRITE,
            Capability.SESSION_REVOKE,
            Capability.SHOP_INFO_MANAGE,
            Capability.TABLE_REPAIR,
            Capability.TIMETRACKING_ALL_READ,
            // Installing a release swaps the running binary, migrates the live fiscal database and
            // restarts the process — the same weight as BACKUP_RESTORE or SYSTEM_CONFIG. MANAGER
            // holds UPDATE_READ so a manager can see that a release is waiting; only an
            // administrator may install it.
            Capability.UPDATE_MANAGE,
        )

    @Test
    fun `the unattached list stays honest`() {
        // If one of these ever does gate a route, the exemption is stale and must be removed rather
        // than left to hide a future orphan.
        val nowAttached = intentionallyUnattached.keys intersect required
        assertTrue(nowAttached.isEmpty(), "Listed as unattached but now gating a route: $nowAttached")
    }
}
