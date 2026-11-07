package org.example.project.features.maintenance

import at.favre.lib.crypto.bcrypt.BCrypt
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MaintenancePolicyTest {
    @AfterEach
    fun reset() {
        MaintenancePolicy.useForTesting(null)
    }

    /** Assumes the machine running the tests exports no `MAINTAINER_*` variables; one that does turns the feature on. */
    @Test
    fun `the checked-in application yaml leaves the feature off`() {
        // No env vars are set in CI or on a developer machine by default.
        MaintenancePolicy.useForTesting(null)
        assertFalse(MaintenancePolicy.enabled)
    }

    @Test
    fun `a test override wins and can be cleared`() {
        val hash = BCrypt.withDefaults().hashToString(4, "pw".toCharArray())
        MaintenancePolicy.useForTesting(MaintenanceConfig("maint", hash, 30))
        assertTrue(MaintenancePolicy.enabled)
        assertEquals(30, MaintenancePolicy.current().sessionMinutes)
        MaintenancePolicy.useForTesting(null)
        assertFalse(MaintenancePolicy.enabled)
    }
}
