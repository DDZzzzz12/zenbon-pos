package org.example.project.core.auth

import org.example.project.core.domain.enums.auth.Assignability
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Invariants that must hold at every point during the Stage 1 migration.
 *
 * These are deliberately structural rather than membership assertions ("CASHIER holds BILL_WRITE"):
 * the bundles start empty and are filled module by module in Task 6, so a membership assertion
 * written now would be a knowingly-failing test. Membership is instead pinned where it becomes
 * true — by `CapabilityEquivalenceTest`, which checks all 504 rules against the frozen legacy
 * verdicts after every module lands.
 */
class CapabilityBundlesTest {
    @Test
    fun `admin holds every capability`() {
        assertEquals(
            Capability.entries.toSet(),
            CapabilityBundles.forRole(EmployeeRole.ADMIN),
            "ADMIN is the bundle form of the plugin's ADMIN short-circuit; it must stay total.",
        )
    }

    @Test
    fun `bundles are monotonically nested`() {
        val terminal = CapabilityBundles.forRole(EmployeeRole.TERMINAL_DEVICE)
        val cashier = CapabilityBundles.forRole(EmployeeRole.CASHIER)
        val manager = CapabilityBundles.forRole(EmployeeRole.MANAGER)
        val admin = CapabilityBundles.forRole(EmployeeRole.ADMIN)

        assertTrue(cashier.containsAll(terminal), "CASHIER must contain TERMINAL_DEVICE: ${terminal - cashier}")
        assertTrue(manager.containsAll(cashier), "MANAGER must contain CASHIER: ${cashier - manager}")
        assertTrue(admin.containsAll(manager), "ADMIN must contain MANAGER: ${manager - admin}")
    }

    @Test
    fun `terminal device holds no reserved capability`() {
        val reserved =
            CapabilityBundles
                .forRole(EmployeeRole.TERMINAL_DEVICE)
                .filter { it.assignability == Assignability.RESERVED }
        assertTrue(reserved.isEmpty(), "TERMINAL_DEVICE must never hold a reserved capability: $reserved")
    }
}
