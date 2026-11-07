package org.example.project.ui.screens.reports

import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.auth.SetAccessPolicy
import org.example.project.core.domain.enums.auth.Capability
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Z-Bon erstellen" follows `ZBON_CLOSE`, the capability `ZBonRules` puts on `POST /api/z-bon` —
 * not `REPORT_ALL_READ`, which the till used to reuse (T5-5).
 */
class CreateZBonGateTest {
    @Test
    fun `REPORT_ALL_READ alone does not offer closing the day`() {
        assertFalse(mayCreateZBon(SetAccessPolicy(setOf(Capability.REPORT_ALL_READ))))
    }

    @Test
    fun `the auditor and accountant groups see every report but may not close the day`() {
        listOf("AUDITOR", "ACCOUNTANT").forEach { code ->
            val held = requireNotNull(CapabilityBundles.forGroup(code)) { "no bundle $code" }
            assertTrue(Capability.REPORT_ALL_READ in held, "$code must still see shop-wide reports")
            assertFalse(mayCreateZBon(SetAccessPolicy(held)), "$code was offered Z-Bon erstellen")
        }
    }

    @Test
    fun `ZBON_CLOSE offers it`() {
        assertTrue(mayCreateZBon(SetAccessPolicy(setOf(Capability.ZBON_CLOSE))))
    }
}
