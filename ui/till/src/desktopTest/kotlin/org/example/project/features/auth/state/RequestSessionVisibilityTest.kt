package org.example.project.features.auth.state

import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.auth.ElevationScope
import org.example.project.core.auth.SetAccessPolicy
import org.example.project.core.auth.StationClass
import org.example.project.core.auth.TerminalMasks
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.features.auth.presentation.state.TemporarySessionUiState
import org.example.project.features.auth.presentation.state.offersRequestSession
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one rule behind the "request session" button on all three nav shells (design §3.2): offered
 * whenever no temporary session is active and the principal lacks at least one of what it could be
 * lent — [ElevationScope.TILL_OVERRIDES] at a till, [ElevationScope.STATION_OVERRIDES] on a station. It used to be a `TERMINAL_DEVICE` role literal, which the
 * order-station shell (2026-09-06) removed — and the case that removal must not regress is the
 * low-privilege PERSON in the TERMINAL_DEVICE group, who is not a station and still needs the
 * override entry.
 */
class RequestSessionVisibilityTest {
    private val idle = TemporarySessionUiState()

    private val active =
        TemporarySessionUiState(
            activeSession =
                AuthDto.TemporarySessionResponseDto(
                    sessionId = "s-1",
                    managerId = "m-1",
                    managerName = "Manager",
                    managerRole = EmployeeRole.MANAGER.name,
                    deviceId = "d-1",
                    requestingEmployeeId = "e-1",
                    expiresAt = "2026-09-06T12:03:00Z",
                    remainingSeconds = 180,
                ),
            remainingSeconds = 180,
        )

    /**
     * Owner decision 2026-10-03 (emu21 NOTE-v) reverses NOTE-10: a station never requests a Storno
     * approval, so the button is how a manager signs in temporarily on the station to Storno or
     * transfer. The station holds neither of [ElevationScope.STATION_OVERRIDES], so it is offered.
     */
    @Test
    fun `a station is offered the manager sign-in while it lacks the station overrides`() {
        val station = TerminalMasks.apply(CapabilityBundles.forRole(EmployeeRole.TERMINAL_DEVICE), StationClass.ORDER_STATION)
        assertTrue(ElevationScope.STATION_OVERRIDES.none { it in station }, "the premise: the mask holds no Storno/transfer override")
        assertFalse(Capability.SELF_WRITE in station, "the station still holds no SELF_WRITE of its own")

        assertTrue(idle.offersRequestSession(SetAccessPolicy(station), isStation = true))
    }

    @Test
    fun `a station is asked only about the station overrides, never the till overrides`() {
        // A station holding both station overrides lacks BILL_VOID etc. — but a station sign-in can
        // never lend those, so the button must not be offered for them.
        val withStationOverrides = SetAccessPolicy(ElevationScope.STATION_OVERRIDES)

        assertFalse(idle.offersRequestSession(withStationOverrides, isStation = true))
        assertTrue(idle.offersRequestSession(withStationOverrides, isStation = false), "a till is still short of the till overrides")
    }

    @Test
    fun `an active manager sign-in on the station hides the button`() {
        assertFalse(active.offersRequestSession(SetAccessPolicy(emptySet()), isStation = true))
    }

    @Test
    fun `a person in the terminal group is a person and keeps the button`() {
        // The regression the isStation-only condition would have introduced: same group as the
        // station, but a human who logs in by username and needs a manager for an item cancel.
        val human = SetAccessPolicy(CapabilityBundles.forRole(EmployeeRole.TERMINAL_DEVICE))

        assertTrue(idle.offersRequestSession(human, isStation = false))
    }

    /**
     * The population the rule change actually widened.
     *
     * The condition was a `TERMINAL_DEVICE` role literal, so in production the button reached
     * stations and nobody else. Following [ElevationScope.TILL_OVERRIDES] instead offers it to
     * every principal short of one override — which is every CASHIER till, the ordinary case, and
     * the reason the button exists at all. Asserted from the real bundle rather than a synthetic
     * "all overrides minus one" set, so seeding an override into CASHIER cannot silently take the
     * manager-override entry away from the till without failing here.
     */
    @Test
    fun `a cashier lacks the manager overrides and is offered the button`() {
        val cashier = CapabilityBundles.forRole(EmployeeRole.CASHIER)
        val lacking = ElevationScope.TILL_OVERRIDES.filterNot { it in cashier }
        assertTrue(lacking.isNotEmpty(), "the premise: a cashier is short of at least one till override")

        assertTrue(idle.offersRequestSession(SetAccessPolicy(cashier), isStation = false))
    }

    @Test
    fun `a manager holds every override and is not offered the button`() {
        val manager = CapabilityBundles.forRole(EmployeeRole.MANAGER)
        assertTrue(ElevationScope.TILL_OVERRIDES.all { it in manager }, "a manager holds the whole till-override set")

        assertFalse(idle.offersRequestSession(SetAccessPolicy(manager), isStation = false))
    }

    @Test
    fun `holding some overrides is still short of one and keeps the button`() {
        val some = ElevationScope.TILL_OVERRIDES - Capability.BILL_VOID

        assertTrue(idle.offersRequestSession(SetAccessPolicy(some), isStation = false))
        assertFalse(idle.offersRequestSession(SetAccessPolicy(ElevationScope.TILL_OVERRIDES), isStation = false))
    }

    @Test
    fun `an active session hides the button whatever is held`() {
        assertFalse(active.offersRequestSession(SetAccessPolicy(emptySet()), isStation = false))
    }

    @Test
    fun `no session manager on the shell offers nothing`() {
        val state: TemporarySessionUiState? = null

        assertFalse(state.offersRequestSession(SetAccessPolicy(emptySet()), isStation = false))
    }
}
