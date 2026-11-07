package org.example.project.ui.navigation

import org.example.project.core.auth.AccessPolicy
import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.auth.SetAccessPolicy
import org.example.project.core.auth.StationClass
import org.example.project.core.auth.TerminalMasks
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Gating predicate that both the navigation menu and MainScreen's "leave a screen the
 * role can no longer access" guard depend on.
 *
 * Two axes: the identity axis is the capability set; the station axis is `isStation`, the client
 * mirror of the server's `station` JWT claim (order-station shell, 2026-09-06). A person whose
 * group happens to be TERMINAL_DEVICE is a person, and the cases at the bottom say so.
 */
class PosNavDestinationAccessTest {
    /** The capability set a role confers, exactly as the fallback leg of `resolveAccessPolicy` would produce it. */
    private fun policyFor(role: EmployeeRole?): AccessPolicy = SetAccessPolicy(role?.let { CapabilityBundles.forRole(it) } ?: emptySet())

    /** What the server reports to an order station: the TERMINAL_DEVICE group narrowed by the station mask. */
    private val stationPolicy =
        SetAccessPolicy(TerminalMasks.apply(CapabilityBundles.forRole(EmployeeRole.TERMINAL_DEVICE), StationClass.ORDER_STATION))

    private fun PosNavDestination.accessibleTo(
        policy: AccessPolicy,
        role: EmployeeRole?,
        isStation: Boolean = false,
    ) = isAccessibleTo(policy, role, voucherEnabled = true, isStation = isStation)

    @Test
    fun reports_isNotAccessibleToTerminal() {
        // When a temp manager session expires the role reverts to TERMINAL_DEVICE, whose group
        // holds no REPORT_READ — the Reports screen must close.
        assertFalse(PosNavDestination.REPORTS.accessibleTo(policyFor(EmployeeRole.TERMINAL_DEVICE), EmployeeRole.TERMINAL_DEVICE))
    }

    @Test
    fun reports_isAccessibleToCashierAndManager() {
        assertTrue(PosNavDestination.REPORTS.accessibleTo(policyFor(EmployeeRole.CASHIER), EmployeeRole.CASHIER))
        assertTrue(PosNavDestination.REPORTS.accessibleTo(policyFor(EmployeeRole.MANAGER), EmployeeRole.MANAGER))
        assertTrue(PosNavDestination.REPORTS.accessibleTo(policyFor(EmployeeRole.ADMIN), EmployeeRole.ADMIN))
    }

    @Test
    fun managerOnlyDestinations_requireManagerOrHigher() {
        assertFalse(PosNavDestination.FINANCIAL_DOCS.accessibleTo(policyFor(EmployeeRole.CASHIER), EmployeeRole.CASHIER))
        assertTrue(PosNavDestination.FINANCIAL_DOCS.accessibleTo(policyFor(EmployeeRole.MANAGER), EmployeeRole.MANAGER))
    }

    @Test
    fun destinationSet_isTheTillSurface() {
        assertEquals(
            listOf("DASHBOARD", "RESERVATIONS", "NOTIFICATIONS", "REPORTS", "FINANCIAL_DOCS", "MY_PROFILE", "SETTINGS"),
            PosNavDestination.entries.map { it.name },
        )
    }

    @Test
    fun myProfile_isSelfServiceNotTeamManagement() {
        assertEquals(NavGroup.UTILITY, PosNavDestination.MY_PROFILE.group)
        assertTrue(PosNavDestination.MY_PROFILE.accessibleTo(policyFor(EmployeeRole.CASHIER), EmployeeRole.CASHIER))
    }

    @Test
    fun terminalVisibleDestinations_alwaysAccessible() {
        assertTrue(PosNavDestination.DASHBOARD.accessibleTo(stationPolicy, EmployeeRole.TERMINAL_DEVICE, isStation = true))
        // A null role (no active session) is treated as station-level.
        assertTrue(PosNavDestination.DASHBOARD.accessibleTo(policyFor(null), null))
        assertFalse(PosNavDestination.REPORTS.accessibleTo(policyFor(null), null))
    }

    @Test
    fun anOrderStationSeesDashboardReservationsAndNotifications() {
        // The station shell, in one assertion. Settings is withheld by the STATION axis, not the
        // capability one: the mask keeps CONFIG_READ because a station has to read its config, but
        // nothing on a station needs local settings.
        val visible =
            PosNavDestination.entries.filter { it.accessibleTo(stationPolicy, EmployeeRole.TERMINAL_DEVICE, isStation = true) }
        // Sets, not lists: the shell is *which* destinations, never their declaration order, and a
        // list comparison turns a harmless enum reorder into a failure that reads like a gating bug.
        assertEquals(
            setOf(PosNavDestination.DASHBOARD, PosNavDestination.RESERVATIONS, PosNavDestination.NOTIFICATIONS),
            visible.toSet(),
        )
    }

    /**
     * The UTILITY group through the gate the rail and the sidebar now call for it.
     *
     * Both used to render Settings from a hardcoded `if (!isStation)` beside the gated PRIMARY
     * loop, so this filter — the one the enum flag feeds — was never what decided whether Settings
     * appeared. Two populations, one filter: a station is offered nothing, and a person holding
     * CONFIG_READ is offered Settings.
     */
    @Test
    fun `the utility group through the gate withholds settings from a station only`() {
        fun utilityFor(
            policy: AccessPolicy,
            role: EmployeeRole?,
            isStation: Boolean,
        ) = PosNavDestination.entries
            .filter { it.group == NavGroup.UTILITY }
            .filter { it.accessibleTo(policy, role, isStation) }
            .toSet()

        assertEquals(
            emptySet(),
            utilityFor(stationPolicy, EmployeeRole.TERMINAL_DEVICE, isStation = true),
            "a station has no profile and no local settings",
        )

        val manager = CapabilityBundles.forRole(EmployeeRole.MANAGER)
        assertTrue(Capability.CONFIG_READ in manager, "the premise: a manager may read the configuration")
        assertEquals(
            setOf(PosNavDestination.MY_PROFILE, PosNavDestination.SETTINGS),
            utilityFor(SetAccessPolicy(manager), EmployeeRole.MANAGER, isStation = false),
        )
    }

    /**
     * The capability hole the hardcoded item left open: Settings reached any person, whatever their
     * group said, because that item never went through the filter.
     *
     * The set is built by narrowing a seeded one rather than named: every group seeded today holds
     * `CONFIG_READ` (it sits in the TERMINAL_DEVICE base every other bundle unions), so there is no
     * shipped group to point at — but a purpose-built group without it is exactly what the
     * permission-group table exists to allow, and the menu must not offer what the server refuses.
     */
    @Test
    fun `a person without CONFIG_READ is not offered settings`() {
        val narrowed = CapabilityBundles.forGroup("ORDER_ONLY")!! - Capability.CONFIG_READ
        assertFalse(Capability.CONFIG_READ in narrowed, "the premise: this group may not read the configuration")

        assertFalse(PosNavDestination.SETTINGS.accessibleTo(SetAccessPolicy(narrowed), EmployeeRole.CASHIER))
        // Not a blanket denial — the rest of the utility group is unaffected.
        assertTrue(PosNavDestination.MY_PROFILE.accessibleTo(SetAccessPolicy(narrowed), EmployeeRole.CASHIER))
    }

    @Test
    fun aPersonInTheTerminalGroupIsAPersonAndSeesTheirProfile() {
        // Accepted consequence of the sweep (design §3.3): logging in by username with the
        // TERMINAL_DEVICE group is a person at a till. They gain My Profile; everything else they
        // cannot reach is withheld by their group, not by the role name.
        val human = policyFor(EmployeeRole.TERMINAL_DEVICE)
        assertTrue(PosNavDestination.MY_PROFILE.accessibleTo(human, EmployeeRole.TERMINAL_DEVICE, isStation = false))
        assertTrue(PosNavDestination.SETTINGS.accessibleTo(human, EmployeeRole.TERMINAL_DEVICE, isStation = false))
        assertFalse(PosNavDestination.MY_PROFILE.accessibleTo(stationPolicy, EmployeeRole.TERMINAL_DEVICE, isStation = true))
        assertFalse(PosNavDestination.SETTINGS.accessibleTo(stationPolicy, EmployeeRole.TERMINAL_DEVICE, isStation = true))
    }

    @Test
    fun anAccountantKeepsTheLedgerAndLosesTheFloor() {
        val accountant = SetAccessPolicy(CapabilityBundles.forGroup("ACCOUNTANT")!!)
        assertTrue(PosNavDestination.FINANCIAL_DOCS.accessibleTo(accountant, EmployeeRole.MANAGER))
        assertFalse(PosNavDestination.RESERVATIONS.accessibleTo(accountant, EmployeeRole.MANAGER))
    }

    @Test
    fun anOrderOnlyEmployeeSeesNoReports() {
        val orderOnly = SetAccessPolicy(CapabilityBundles.forGroup("ORDER_ONLY")!!)
        assertFalse(PosNavDestination.REPORTS.accessibleTo(orderOnly, EmployeeRole.CASHIER))
        assertTrue(PosNavDestination.DASHBOARD.accessibleTo(orderOnly, EmployeeRole.CASHIER))
    }

    @Test
    fun everyDestinationNamesACapabilityOrIsTheDashboard() {
        assertEquals(
            listOf(PosNavDestination.DASHBOARD),
            PosNavDestination.entries.filter { it.requiredCapability == null },
        )
    }
}
