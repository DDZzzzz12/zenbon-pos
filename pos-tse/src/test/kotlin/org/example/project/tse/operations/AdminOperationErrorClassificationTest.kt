package org.example.project.tse.operations

import com.secureflashcard.wormapi.WormUserId
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.admin.clients.ListClientsOperation
import org.example.project.tse.virtual.VirtualTseTestSupport
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * E2E R2-6, admin path: the rule the signing path already follows must hold for administration too.
 *
 * `BaseTseOperation` treated every `WormException` as lost hardware — it called
 * `handleHardwareError`, which clears the mount point, and reported TSE_CONNECTION_FAILED. So a
 * card that had merely refused a request was recorded as an unreachable device, and the *next*
 * admin call failed until the connection was re-acquired. Thirty-two operations inherit that
 * class, including the commissioning ones, which is where it hurts: the device is at its least
 * ready exactly when an operator is setting it up.
 *
 * Both directions are asserted here. The card-answer cases are the behaviour that changed; the
 * host-fault case is the behaviour that must NOT change, and it is the reason the virtual device
 * gained a fault hook — see [VirtualTseTestSupport.armHostFault]. A classification error in that
 * direction would leave the POS talking to a device that is no longer there, and without this test
 * nothing in the suite would notice.
 */
class AdminOperationErrorClassificationTest {
    private val tseStatus = TseStatus()

    @AfterEach
    fun tearDown() {
        tseStatus.close()
        VirtualTseTestSupport.detachDevice()
    }

    /** Attaches a virtual TSE and connects to it. [commission] runs full setup, so a login works. */
    private fun connected(commission: Boolean) {
        VirtualTseTestSupport.attachDevice("v2")
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        tseStatus.setMode(TseMode.NORMAL)
        tseStatus.setClientId("TER-001")
        tseStatus.setTimeAdminPin("98765")
        tseStatus.setCredentialSeed("SwissbitSwissbit")
        assertTrue(tseStatus.detectHardware())
        if (!commission) return
        val store = tseStatus.getWormStore()
        store.tse_setup_ext(
            "SwissbitSwissbit".toByteArray(),
            "888888".toByteArray(),
            "54321".toByteArray(),
            "98765".toByteArray(),
            "TER-001",
        )
        store.tse_updateTime(System.currentTimeMillis() / 1000)
        runCatching { store.user_logout(WormUserId.WORM_USER_ADMIN) }
    }

    private fun assertConnectionKept() {
        assertEquals(
            VirtualTseTestSupport.MOUNT_POINT,
            tseStatus.getMountPoint(),
            "the mount point must survive a card answer",
        )
        assertTrue(tseStatus.isConnected(), "the connection must survive a card answer")
    }

    @Test
    fun `an uncommissioned TSE is not operational and keeps its mount point`() {
        connected(commission = false)

        val e = assertFailsWith<PosException> { ListClientsOperation(adminPin = "54321").execute(tseStatus) }

        assertEquals(PosErrorCode.TSE_NOT_OPERATIONAL, e.code)
        assertConnectionKept()
    }

    /**
     * A wrong PIN is the clearest case of all: the card not only answered, it counted the attempt.
     * Reporting that as a connection failure also hid the one thing the operator needed to know.
     */
    @Test
    fun `a rejected admin PIN keeps its mount point`() {
        connected(commission = true)

        val e = assertFailsWith<PosException> { ListClientsOperation(adminPin = "00000").execute(tseStatus) }

        assertEquals(PosErrorCode.TSE_OPERATION_REJECTED, e.code)
        assertConnectionKept()
    }

    /**
     * The negative control. `WORM_ERROR_NO_WORM_CARD` is code 2 — below the SDK's `0x1000`
     * boundary, so it means the host lost the device rather than the device refusing something.
     * This must still reset, and it passed before the change as well: that is the point of it.
     */
    @Test
    fun `a host-side fault still resets the connection`() {
        connected(commission = true)
        VirtualTseTestSupport.armHostFault("WORM_ERROR_NO_WORM_CARD")

        val e = assertFailsWith<PosException> { ListClientsOperation(adminPin = "54321").execute(tseStatus) }

        assertEquals(PosErrorCode.TSE_CONNECTION_FAILED, e.code)
        assertNull(tseStatus.getMountPoint(), "a lost device must clear the mount point")
        assertFalse(tseStatus.isConnected(), "a lost device must drop the connection")
    }

    /** A timeout is host-side too, and carries its own code so a retry can tell it apart. */
    @Test
    fun `a timeout still resets the connection and reports itself as one`() {
        connected(commission = true)
        VirtualTseTestSupport.armHostFault("WORM_ERROR_TIMEOUT")

        val e = assertFailsWith<PosException> { ListClientsOperation(adminPin = "54321").execute(tseStatus) }

        assertEquals(PosErrorCode.TSE_COMMUNICATION_TIMEOUT, e.code)
        assertFalse(tseStatus.isConnected(), "a timeout must drop the connection")
    }

    /**
     * The reset is only half of the contract: once the stick is back, administration must work
     * again through the ordinary re-acquisition path, against the same still-commissioned device.
     * While the fault is armed re-acquisition itself must fail — a POS that "reconnected" to a
     * device that is gone would be worse than one that stays honestly disconnected.
     */
    @Test
    fun `an admin operation recovers once the hardware fault clears`() {
        connected(commission = true)
        VirtualTseTestSupport.armHostFault("WORM_ERROR_NO_WORM_CARD")
        assertFailsWith<PosException> { ListClientsOperation(adminPin = "54321").execute(tseStatus) }

        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        assertFalse(tseStatus.isConnected(), "a device that is still gone must not be re-acquired")

        VirtualTseTestSupport.clearFault()
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        assertTrue(tseStatus.isConnected(), "the device must be re-acquired once it is back")

        val result = ListClientsOperation(adminPin = "54321").execute(tseStatus)

        assertTrue("TER-001" in result.clients, "the re-plugged device is the same commissioned one")
    }
}
