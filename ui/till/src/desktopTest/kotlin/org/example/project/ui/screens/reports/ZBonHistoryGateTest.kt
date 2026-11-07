package org.example.project.ui.screens.reports

import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.auth.SetAccessPolicy
import org.example.project.core.domain.enums.auth.Capability
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The till's "Z-Bon Verlauf" tab follows `ZBON_MANAGE`, the capability `ZBonRules` puts on every
 * route the tab reads (`/recent`, `/{id}`, `/history`, `/{id}/pdf`) — not `REPORT_ALL_READ`, which it
 * used to reuse, so a group with shop-wide reports but no closing documents opened a tab of 403s.
 */
class ZBonHistoryGateTest {
    @Test
    fun `REPORT_ALL_READ alone does not offer the Z-Bon history`() {
        assertFalse(mayReadZBonHistory(SetAccessPolicy(setOf(Capability.REPORT_ALL_READ))))
    }

    @Test
    fun `ZBON_MANAGE offers it`() {
        assertTrue(mayReadZBonHistory(SetAccessPolicy(setOf(Capability.ZBON_MANAGE))))
    }

    @Test
    fun `every stock group with shop-wide reports can still read the closings`() {
        listOf("MANAGER", "AUDITOR", "ACCOUNTANT").forEach { code ->
            val held = requireNotNull(CapabilityBundles.forGroup(code)) { "no bundle $code" }
            assertTrue(Capability.REPORT_ALL_READ in held, "$code must see shop-wide reports")
            assertTrue(mayReadZBonHistory(SetAccessPolicy(held)), "$code lost the Z-Bon history")
        }
    }
}
