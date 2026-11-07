package org.example.project.infrastructure.security

import io.ktor.http.HttpMethod
import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #1 — who may hand a guest a second copy of their receipt.
 *
 * `POST /api/v1/bills/{billId}/print` used to carry `BILL_WRITE`, the capability that gates
 * *creating* a bill. The effect was that a waiter — role `TERMINAL_DEVICE`, who holds `BILL_READ`
 * and can therefore open the receipt — was refused when they pressed Print, and only a cashier
 * could reprint. It now carries `PRINTER_WRITE`; see `BillRules` for why that is the right question
 * to ask rather than a widening of `BILL_WRITE`.
 *
 * Asserted against the registry and the bundles rather than through a live call, so this is the
 * server half of CLAUDE.md rule 8 in a form that cannot rot: the composeApp control is gated on the
 * same `PRINTER_WRITE` in `PosBillCreatedActionsDialog`.
 */
@DisplayName("Reprinting a receipt is floor work, not cashier work")
class ReceiptReprintReachTest {
    private val printRule = "/api/v1/bills/{billId}/print" to HttpMethod.Post

    private fun ruleFor(rule: Pair<String, HttpMethod>): AccessLevel =
        RoutePermissions.findRule(rule.first.replace(Regex("\\{[^}]*}"), "1"), rule.second)
            ?: error("no registered rule for ${rule.second.value} ${rule.first} — an unregistered route is denied to everyone")

    private fun canReach(
        rule: Pair<String, HttpMethod>,
        capabilities: Set<Capability>,
    ): Boolean =
        when (val level = ruleFor(rule)) {
            is AccessLevel.Public -> true
            is AccessLevel.Cap -> level.capability in capabilities
            is AccessLevel.CapWithOwnership -> capabilities.any { it in level.anyOf }
            is AccessLevel.AllOf -> level.allOf.all { it in capabilities }
            is AccessLevel.Maintainer -> false
        }

    @Test
    fun `a waiter can reprint a receipt`() {
        // The reported bug, stated as the behaviour it should have had. TERMINAL_DEVICE is the role
        // a floor employee who does not take payment carries.
        assertTrue(
            canReach(printRule, CapabilityBundles.forRole(EmployeeRole.TERMINAL_DEVICE)),
            "a non-cashier employee cannot reprint a customer receipt — this is issue #1",
        )
    }

    @Test
    fun `every role that could reprint before still can`() {
        listOf(EmployeeRole.CASHIER, EmployeeRole.MANAGER, EmployeeRole.ADMIN).forEach { role ->
            assertTrue(
                canReach(printRule, CapabilityBundles.forRole(role)),
                "$role lost the ability to reprint — the fix was meant to widen the route, not move it",
            )
        }
    }

    @Test
    fun `the back office does not gain a till action`() {
        // Widening a route is only safe if it is bounded. AUDITOR holds BILL_READ, so gating the
        // reprint on that capability instead would have handed a read-only audit account a control
        // over the shop's hardware; PRINTER_WRITE is in neither back-office group.
        listOf("AUDITOR", "ACCOUNTANT").forEach { code ->
            val capabilities = CapabilityBundles.forGroup(code) ?: error("no seeded group '$code'")
            assertFalse(
                canReach(printRule, capabilities),
                "$code can drive the receipt printer; PRINTER_WRITE is meant to be floor-only",
            )
        }
    }

    @Test
    fun `the rule names the capability the till control asks for`() {
        // Pinned by identity, not just by reachability: composeApp's print button is gated on
        // `holds(Capability.PRINTER_WRITE)`, and a silent change of the capability here would leave
        // the two levels asking different questions while every reachability assertion above still
        // passed.
        assertEquals(AccessLevel.Cap(Capability.PRINTER_WRITE), ruleFor(printRule))
    }
}
