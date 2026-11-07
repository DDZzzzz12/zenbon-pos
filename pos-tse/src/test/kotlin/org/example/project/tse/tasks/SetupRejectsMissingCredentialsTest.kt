package org.example.project.tse.tasks

import com.secureflashcard.wormapi.WormInitializationState
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.tse.models.TseStatus
import org.example.project.tse.virtual.VirtualTseTestSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/**
 * Setup must refuse a missing input before it writes anything to the device.
 *
 * Everything past the precondition is either irreversible (`user_change_puk`,
 * `user_change_pin`) or spends the single-use credential seed, so "fails cleanly" is not
 * enough — it has to fail while the TSE is still untouched and still commissionable with
 * the right values.
 */
class SetupRejectsMissingCredentialsTest {

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
        tseStatus.setTimeAdminPin("98765")
        assertTrue(tseStatus.detectHardware())
    }

    private fun assertStillUninitialized() {
        tseStatus.getWormStore().info().use {
            assertEquals(
                WormInitializationState.WORM_INIT_UNINITIALIZED,
                it.initializationState(),
                "setup must not have written to the device",
            )
        }
    }

    @Test
    fun `setup without a client id is refused on TSE 2_0`() {
        attach("v2")

        // Deliberately no setClientId: this is the production shape, where nothing populates it.
        val ok = TseInitializationManager(tseStatus).setUp(
            adminPin = "54321",
            adminPuk = "888888",
            credentialSeed = "SwissbitSwissbit",
            clientId = null,
        )

        assertFalse(ok, "setup must refuse a missing client ID")
        assertStillUninitialized()
    }

    @Test
    fun `setup without a client id is refused on TSE 1_x`() {
        attach("v1")

        val ok = TseInitializationManager(tseStatus).setUp(
            adminPin = "54321",
            adminPuk = "888888",
            credentialSeed = "SwissbitSwissbit",
            clientId = null,
        )

        assertFalse(ok, "setup must refuse a missing client ID")
        assertStillUninitialized()
    }

    @Test
    fun `setup with a blank admin pin or puk is refused`() {
        attach("v2")

        assertFalse(
            TseInitializationManager(tseStatus).setUp(
                adminPin = "", adminPuk = "888888",
                credentialSeed = "SwissbitSwissbit", clientId = "TER-001",
            ),
            "a blank Admin PIN would be written to the device one-way",
        )
        assertStillUninitialized()

        assertFalse(
            TseInitializationManager(tseStatus).setUp(
                adminPin = "54321", adminPuk = "",
                credentialSeed = "SwissbitSwissbit", clientId = "TER-001",
            ),
            "a blank Admin PUK would leave no way back from a blocked PIN",
        )
        assertStillUninitialized()
    }

    @Test
    fun `setup with a blank credential seed is refused`() {
        attach("v2")

        val ok = TseInitializationManager(tseStatus).setUp(
            adminPin = "54321",
            adminPuk = "888888",
            credentialSeed = "",
            clientId = "TER-001",
        )

        assertFalse(ok, "setup must refuse a blank credential seed")
        assertStillUninitialized()
    }

    @Test
    fun `setup is refused when a credential is still a factory placeholder`() {
        attach("v2")

        // Blank is not the only unusable value. The shipped configuration carries "12345" for
        // both PINs and "123456" for the PUK, and setup writes them into the device one-way —
        // so a placeholder that slips through is permanent.
        assertFalse(
            TseInitializationManager(tseStatus).setUp(
                adminPin = "12345", adminPuk = "888888",
                credentialSeed = "SwissbitSwissbit", clientId = "TER-001",
            ),
            "the default Admin PIN must not be written to a device",
        )
        assertStillUninitialized()

        assertFalse(
            TseInitializationManager(tseStatus).setUp(
                adminPin = "54321", adminPuk = "123456",
                credentialSeed = "SwissbitSwissbit", clientId = "TER-001",
            ),
            "the default Admin PUK must not be written to a device",
        )
        assertStillUninitialized()
    }

    @Test
    fun `setup is refused when the TimeAdmin PIN is still a factory placeholder`() {
        attach("v2")
        // The one credential the ERS is allowed to keep on disk, so the one most worth
        // choosing — an unchanged default is what an attacker reading the database gets.
        tseStatus.setTimeAdminPin("12345")

        assertFalse(
            TseInitializationManager(tseStatus).setUp(
                adminPin = "54321", adminPuk = "888888",
                credentialSeed = "SwissbitSwissbit", clientId = "TER-001",
            ),
        )
        assertStillUninitialized()
    }

    @Test
    fun `a fully specified setup still succeeds`() {
        attach("v2")

        // The guard must not have made commissioning unreachable.
        val ok = TseInitializationManager(tseStatus).setUp(
            adminPin = "54321",
            adminPuk = "888888",
            credentialSeed = "SwissbitSwissbit",
            clientId = "TER-001",
        )

        assertTrue(ok, "a complete setup must still work")
        tseStatus.getWormStore().info().use {
            assertEquals(WormInitializationState.WORM_INIT_INITIALIZED, it.initializationState())
        }
    }
}
