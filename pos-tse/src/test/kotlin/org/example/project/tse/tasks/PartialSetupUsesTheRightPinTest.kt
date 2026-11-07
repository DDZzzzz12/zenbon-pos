package org.example.project.tse.tasks

import com.secureflashcard.wormapi.WormInitializationState
import com.secureflashcard.wormapi.WormUserId
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.tse.models.TseStatus
import org.example.project.tse.virtual.VirtualTseTestSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/**
 * Recovery must log in with the PIN the device actually holds.
 *
 * "Some credential changed" is not "the Admin PIN changed". A setup interrupted after the
 * PUK change still holds the seed-derived Admin PIN, and recovery used to log in with the
 * configured one regardless — a wrong-PIN attempt, repeated on every restart, against the
 * three the device grants before it blocks and needs its PUK.
 *
 * The tests interrupt setup at each step and then run recovery, which must both succeed and
 * finish the credential changes that were left outstanding.
 */
class PartialSetupUsesTheRightPinTest {

    private val tseStatus = TseStatus()

    private val seed = "SwissbitSwissbit"
    private val newPuk = "888888"
    private val newAdminPin = "54321"
    private val newTimeAdminPin = "98765"

    @AfterEach
    fun tearDown() {
        tseStatus.close()
        VirtualTseTestSupport.detachDevice()
    }

    private fun connect() {
        VirtualTseTestSupport.attachDevice("v2")
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        tseStatus.setMode(TseMode.NORMAL)
        tseStatus.setClientId("TER-001")
        tseStatus.setTimeAdminPin(newTimeAdminPin)
        tseStatus.setCredentialSeed(seed)
        assertTrue(tseStatus.detectHardware())
    }

    /** Changes only the PUK, leaving both PINs seed-derived — setup interrupted after step 2. */
    private fun interruptAfterPukChange() {
        val store = tseStatus.getWormStore()
        store.user_deriveInitialCredentials(seed.toByteArray()).use { creds ->
            store.user_change_puk(creds.adminPuk(), newPuk.toByteArray())
        }
    }

    /** Changes the PUK and the Admin PIN, leaving TimeAdmin seed-derived. */
    private fun interruptAfterAdminPinChange() {
        val store = tseStatus.getWormStore()
        store.user_deriveInitialCredentials(seed.toByteArray()).use { creds ->
            store.user_change_puk(creds.adminPuk(), newPuk.toByteArray())
            // Changing a PIN requires that user to be logged in — the same rule the recovery
            // path has to honour.
            store.user_login(WormUserId.WORM_USER_ADMIN, creds.adminPin())
            store.user_change_pin(WormUserId.WORM_USER_ADMIN, creds.adminPin(), newAdminPin.toByteArray())
            runCatching { store.user_logout(WormUserId.WORM_USER_ADMIN) }
        }
    }

    private fun runSetup(): Boolean = TseInitializationManager(tseStatus).setUp(
        adminPin = newAdminPin,
        adminPuk = newPuk,
        credentialSeed = seed,
        clientId = "TER-001",
    )

    @Test
    fun `recovers when only the PUK was changed`() {
        connect()
        interruptAfterPukChange()

        assertTrue(runSetup(), "recovery must log in with the seed-derived Admin PIN, not the configured one")

        tseStatus.getWormStore().info().use {
            assertEquals(WormInitializationState.WORM_INIT_INITIALIZED, it.initializationState())
            assertTrue(it.hasChangedAdminPin(), "the outstanding Admin PIN change must be completed")
            assertTrue(it.hasChangedTimeAdminPin(), "the outstanding TimeAdmin PIN change must be completed")
        }
    }

    @Test
    fun `recovers when the PUK and Admin PIN were changed`() {
        connect()
        interruptAfterAdminPinChange()

        assertTrue(runSetup())

        tseStatus.getWormStore().info().use {
            assertEquals(WormInitializationState.WORM_INIT_INITIALIZED, it.initializationState())
            assertTrue(it.hasChangedTimeAdminPin(), "TimeAdmin was still seed-derived and must be changed")
        }
    }

    @Test
    fun `the configured Admin PIN works after recovery`() {
        connect()
        interruptAfterPukChange()
        assertTrue(runSetup())

        val store = tseStatus.getWormStore()
        runCatching { store.user_logout(WormUserId.WORM_USER_ADMIN) }

        // The whole point: the operator's chosen PIN is what the device ends up holding.
        store.user_login(WormUserId.WORM_USER_ADMIN, newAdminPin.toByteArray())
    }

    @Test
    fun `the configured TimeAdmin PIN works after recovery`() {
        connect()
        interruptAfterPukChange()
        assertTrue(runSetup())

        val store = tseStatus.getWormStore()
        runCatching { store.user_logout(WormUserId.WORM_USER_ADMIN) }

        // Unattended time sync depends on this one, so a missed change would surface much
        // later as a TimeAdmin auth failure rather than as a setup problem.
        store.user_login(WormUserId.WORM_USER_TIME_ADMIN, newTimeAdminPin.toByteArray())
        runCatching { store.user_logout(WormUserId.WORM_USER_TIME_ADMIN) }
    }
}
