package org.example.project.infrastructure.security

import io.ktor.http.HttpMethod
import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.auth.StationClass
import org.example.project.core.auth.TerminalMasks
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Stage 4: the station axis.
 *
 * The masks answer "which APIs does this station *type* exist to serve", and they can only narrow —
 * the effective set is `identity ∩ mask`. Everything here is about keeping that property true and
 * keeping the masks from bricking the stations they describe.
 */
@DisplayName("Terminal station masks")
class TerminalMaskMatrixTest {
    private val terminalBundle = CapabilityBundles.forRole(EmployeeRole.TERMINAL_DEVICE)

    private fun ruleFor(
        path: String,
        method: HttpMethod,
    ): Capability = (RoutePermissions.findRule(path, method) as AccessLevel.Cap).capability

    @Test
    fun `every station class has a mask`() {
        // `forStation` errors rather than defaulting, so a missing entry is a crash and not a silent
        // full-access fallback. This makes that explicit rather than relying on someone reading it.
        StationClass.entries.forEach { assertTrue(TerminalMasks.forStation(it).isNotEmpty(), "$it has no mask") }
    }

    @Test
    fun `every mask admits the launch gate`() {
        // GET /api/v1/terminals/me-by-device is how a login-less station discovers its own role at
        // startup, before anyone logs in. A mask without TERMINAL_READ bricks that station on boot,
        // and it would look like a pairing bug, not an authorization one.
        //
        // Asserted against the ROUTE rather than the capability name, so moving the launch gate to a
        // different capability fails here instead of silently making this test vacuous.
        val launchGate = ruleFor("/api/v1/terminals/me-by-device", HttpMethod.Get)
        StationClass.entries.forEach { station ->
            assertTrue(
                launchGate in TerminalMasks.forStation(station),
                "$station cannot call the launch gate ($launchGate) and would brick at startup",
            )
        }
    }

    @Test
    fun `an order station can still place an order`() {
        // The one login-less flow that is live today. If this fails, orders stop being takeable.
        val effective = TerminalMasks.apply(terminalBundle, StationClass.ORDER_STATION)
        assertTrue(ruleFor("/api/orders", HttpMethod.Post) in effective)
        assertTrue(Capability.PRINTER_WRITE in effective, "an order that cannot print never reaches the kitchen")
        assertTrue(Capability.TABLE_WRITE in effective, "orders are taken against a table session")
    }

    @Test
    fun `an order station takes reservations but never deletes one`() {
        // The station shell shows the reservations screen: list, create, amend. Deleting stays with
        // the till — RESERVATION_DELETE is deliberately outside the mask.
        val effective = TerminalMasks.apply(terminalBundle, StationClass.ORDER_STATION)
        assertTrue(ruleFor("/api/reservations", HttpMethod.Get) in effective, "the station lists reservations")
        assertTrue(ruleFor("/api/reservations", HttpMethod.Post) in effective, "and takes one")
        assertFalse(ruleFor("/api/reservations/{id}", HttpMethod.Delete) in effective, "deleting stays with the till")
    }

    @Test
    fun `a kitchen display cannot reach reporting or sales`() {
        // The original requirement, in one assertion.
        val effective = TerminalMasks.apply(terminalBundle, StationClass.KITCHEN)
        listOf(
            Capability.REPORT_READ,
            Capability.BILL_READ,
            Capability.ZBON_READ,
            Capability.CUSTOMER_READ,
            Capability.ORDER_WRITE,
            Capability.VOUCHER_READ,
            Capability.PAYROLL_READ,
        ).forEach { assertFalse(it in effective, "a kitchen display has no business with $it") }

        assertTrue(Capability.ORDER_READ in effective, "but it does have to read the queue")
    }

    @Test
    fun `a customer display can only read`() {
        val effective = TerminalMasks.apply(terminalBundle, StationClass.CUSTOMER_DISPLAY)
        val writes = effective.filter { it.name.endsWith("_WRITE") || it.name.endsWith("_MANAGE") }
        assertEquals(emptyList(), writes, "a customer-facing screen must not be able to change anything")
    }

    @Test
    fun `masking only ever narrows`() {
        // The invariant the whole axis rests on. A mask must not be able to hand a station something
        // the identity axis refused it — otherwise the mask is a second, weaker grant mechanism.
        StationClass.entries.forEach { station ->
            val effective = TerminalMasks.apply(terminalBundle, station)
            assertTrue(
                terminalBundle.containsAll(effective),
                "$station's mask granted ${effective - terminalBundle} beyond the identity set",
            )
        }
    }

    @Test
    fun `an empty identity set stays empty whatever the station`() {
        // Fails closed: an unresolved principal is not rescued by standing at a till.
        StationClass.entries.forEach {
            assertEquals(emptySet(), TerminalMasks.apply(emptySet(), it))
        }
    }

    @Test
    fun `CASHIER and UNBOUND are not narrowed`() {
        // Both are reached over a JWT, where the identity axis is the gate. UNBOUND especially:
        // it means "no station was proven", and narrowing it would be defeated by sending a garbage
        // device id — which would make the axis decorative while looking strict.
        listOf(StationClass.CASHIER, StationClass.UNBOUND).forEach {
            assertEquals(Capability.entries.toSet(), TerminalMasks.forStation(it), "$it must not be narrowed")
        }
    }

    @Test
    fun `every login-less terminal role maps to a narrowed mask`() {
        // Ties the two enums together. A new login-less TerminalRole added without a mask would fall
        // to UNBOUND — full access — which is precisely the wrong default for a station that
        // authenticates by device token alone.
        TerminalRole.entries.filter { it.loginLess }.forEach { role ->
            val station = StationClass.fromTerminalRole(role.name)
            assertTrue(station != StationClass.UNBOUND, "$role does not map to a StationClass")
            assertTrue(
                TerminalMasks.forStation(station).size < Capability.entries.size,
                "$role is login-less and authenticates by device token alone, so its mask must narrow",
            )
        }
    }

    @Test
    fun `an unrecognised role is UNBOUND, not CASHIER`() {
        // TerminalRole.fromCode defaults unknown input to CASHIER, which is right for provisioning
        // (an unrecognised role should get the safe FISCAL treatment) and wrong here, where it would
        // claim a station identity nothing proved.
        listOf(null, "", "   ", "nonsense", "UNBOUND").forEach {
            assertEquals(StationClass.UNBOUND, StationClass.fromTerminalRole(it), "input: '$it'")
        }
        assertEquals(TerminalRole.CASHIER, TerminalRole.fromCode("nonsense"))
    }

    @Test
    fun `a registered device's class wins over the token's, which is only the fallback`() {
        // The mask's precedence: a device token's terminal decides, and a device that resolved to no
        // terminal — or to an unknown role — must not erase what a station JWT's claim proves. (The
        // billing and pricing rules no longer read this: they key on attribution, 2026-09-28.)
        assertEquals(
            StationClass.ORDER_STATION,
            StationClass.forRequest(TerminalRole.ORDER_STATION.name, fallback = StationClass.UNBOUND),
        )
        assertEquals(
            StationClass.CASHIER,
            StationClass.forRequest(TerminalRole.CASHIER.name, fallback = StationClass.UNBOUND),
        )
        assertEquals(StationClass.UNBOUND, StationClass.forRequest(null, fallback = StationClass.UNBOUND))
        assertEquals(StationClass.ORDER_STATION, StationClass.forRequest(null, fallback = StationClass.ORDER_STATION))
        assertEquals(StationClass.ORDER_STATION, StationClass.forRequest("nonsense", fallback = StationClass.ORDER_STATION))
    }

    @Test
    fun `role names round-trip between the two enums`() {
        TerminalRole.entries.forEach {
            assertEquals(it.name, StationClass.fromTerminalRole(it.name).name, "${it.name} did not round-trip")
        }
    }
}
