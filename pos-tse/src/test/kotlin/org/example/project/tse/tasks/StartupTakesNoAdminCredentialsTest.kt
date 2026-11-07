package org.example.project.tse.tasks

import com.secureflashcard.wormapi.WormInitializationState
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
 * Startup must not take Admin credentials at all — not a wrong one, not any one.
 *
 * [TseStartupV1NoAdminTest] proved startup does not *depend* on the PIN. This proves the
 * stronger property the credential removal actually needs: the signature carries no Admin
 * secret, so the server has nothing to store and nothing to pass. Commissioning an
 * uninitialised TSE is a separate, operator-triggered act with its own entry point.
 */
class StartupTakesNoAdminCredentialsTest {

    private val tseStatus = TseStatus()

    @AfterEach
    fun tearDown() {
        tseStatus.close()
        VirtualTseTestSupport.detachDevice()
    }

    private fun attach(generation: String) {
        VirtualTseTestSupport.attachDevice(generation)
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        tseStatus.setMode(TseMode.NORMAL)
        tseStatus.setClientId("TER-001")
        tseStatus.setTimeAdminPin("98765")
        assertTrue(tseStatus.detectHardware())
    }

    private fun commission() {
        tseStatus.getWormStore().tse_setup_ext(
            "SwissbitSwissbit".toByteArray(), "888888".toByteArray(),
            "54321".toByteArray(), "98765".toByteArray(), "TER-001",
        )
        runCatching { tseStatus.getWormStore().user_logout(WormUserId.WORM_USER_ADMIN) }
    }

    @Test
    fun `startup on a commissioned TSE 2_0 needs no credential argument`() {
        attach("v2")
        commission()

        assertTrue(
            TseInitializationManager(tseStatus).initialize(clientId = "TER-001"),
            "startup must succeed with no Admin credential in hand",
        )
    }

    @Test
    fun `startup on a commissioned TSE 1_x needs no credential argument`() {
        attach("v1")
        commission()

        assertTrue(TseInitializationManager(tseStatus).initialize(clientId = "TER-001"))

        // Setting the time is exactly what startup could not do without a credential, so it is
        // the assertion that matters: TimeAdmin carried it, nobody supplied an Admin PIN.
        tseStatus.getWormStore().info().use {
            assertTrue(it.hasValidTime(), "startup must set the time on its own")
        }
    }

    @Test
    fun `startup on an uninitialised TSE reports failure without commissioning it`() {
        attach("v2")

        assertFalse(
            TseInitializationManager(tseStatus).initialize(clientId = "TER-001"),
            "an uninitialised TSE cannot start up",
        )

        // The point of the assertion: startup must not have quietly commissioned the device.
        // Setup burns the one-time credential seed and fixes the serial into the certificate,
        // so it belongs to an operator who chose it, never to a background restart.
        tseStatus.getWormStore().info().use {
            assertEquals(
                WormInitializationState.WORM_INIT_UNINITIALIZED,
                it.initializationState(),
                "startup must leave an uninitialised TSE untouched",
            )
        }
    }

    @Test
    fun `setup commissions the TSE with operator-supplied credentials`() {
        attach("v2")

        val ok = TseInitializationManager(tseStatus).setUp(
            adminPin = "54321",
            adminPuk = "888888",
            credentialSeed = "SwissbitSwissbit",
            clientId = "TER-001",
        )

        assertTrue(ok, "operator-triggered setup must commission the TSE")
        tseStatus.getWormStore().info().use {
            assertEquals(WormInitializationState.WORM_INIT_INITIALIZED, it.initializationState())
        }
    }
}
