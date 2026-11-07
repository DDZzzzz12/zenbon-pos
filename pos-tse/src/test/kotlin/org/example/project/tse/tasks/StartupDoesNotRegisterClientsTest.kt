package org.example.project.tse.tasks

import com.secureflashcard.wormapi.WormUserId
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.tse.models.TseStatus
import org.example.project.tse.virtual.VirtualTseTestSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/**
 * An unregistered Kasse must fail startup, not get itself registered.
 *
 * Registering a client is an operator-present act — it declares which Kasse this TSE signs
 * for, and the terminal-pairing design puts it behind a confirmation. The auto-registration
 * in ApplyConfigFromMainServer and TseMaintenanceTask was removed for that reason; this path
 * was the last one left, and it also needed an Admin PIN that startup no longer holds, so it
 * would have thrown a NullPointerException on the `!!` before it ever got that far.
 *
 * TSE 1.x only: TSE 2.0 ignores the client for the self test, so it never reaches here.
 */
class StartupDoesNotRegisterClientsTest {

    private val tseStatus = TseStatus()

    @AfterEach
    fun tearDown() {
        tseStatus.close()
        VirtualTseTestSupport.detachDevice()
    }

    /** Commissions a TSE 1.x for one Kasse, then points the driver at a different one. */
    private fun commissionedForADifferentClient() {
        VirtualTseTestSupport.attachDevice("v1")
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        tseStatus.setMode(TseMode.NORMAL)
        tseStatus.setTimeAdminPin("98765")
        tseStatus.setCredentialSeed("SwissbitSwissbit")
        tseStatus.setClientId("TER-001")
        assertTrue(tseStatus.detectHardware())

        tseStatus.getWormStore().tse_setup_ext(
            "SwissbitSwissbit".toByteArray(), "888888".toByteArray(),
            "54321".toByteArray(), "98765".toByteArray(), "TER-001",
        )
        tseStatus.getWormStore().tse_updateTime(System.currentTimeMillis() / 1000)
        runCatching { tseStatus.getWormStore().user_logout(WormUserId.WORM_USER_ADMIN) }

        // The register this driver now claims to be, which the TSE has never heard of.
        tseStatus.setClientId("TER-999")

        // Setup leaves the self test passed, which would short-circuit startup before it ever
        // reached the self test. A real TSE needs one every 25 hours, so this is the ordinary
        // state a restart finds.
        VirtualTseTestSupport.expireSelfTest(tseStatus.getWormStore())
    }

    @Test
    fun `startup fails rather than registering an unknown client`() {
        commissionedForADifferentClient()

        val ok = TseInitializationManager(tseStatus).initialize(clientId = "TER-999")

        assertFalse(ok, "an unregistered Kasse must fail startup, not quietly register itself")
    }

    @Test
    fun `the unknown client is not registered on the device`() {
        commissionedForADifferentClient()
        val before = tseStatus.getWormStore().info().use { it.registeredClients() }

        TseInitializationManager(tseStatus).initialize(clientId = "TER-999")

        val after = tseStatus.getWormStore().info().use { it.registeredClients() }
        assertEquals(
            before, after,
            "startup must not add a client — that is an operator-present act",
        )
    }

    @Test
    fun `a registered client still starts up normally`() {
        commissionedForADifferentClient()
        tseStatus.setClientId("TER-001")

        assertTrue(
            TseInitializationManager(tseStatus).initialize(clientId = "TER-001"),
            "the guard must not have broken the normal path",
        )
    }
}
