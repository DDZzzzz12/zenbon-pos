package org.example.project.tse.models

import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.tse.virtual.VirtualTseTestSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/**
 * A NORMAL TSE that fails to come up must NOT be downgraded to SIMULATION.
 *
 * SIMULATION means signing is mocked and NOT fiscally compliant. Silently switching an
 * uncommissioned-but-NORMAL device into it means every receipt gets a fake signature while
 * the shop believes it is trading normally — far worse than not signing, which at least
 * queues for Nachsignieren. The device must stay NORMAL-but-not-operational so the real sign
 * path queues instead of mocking, and so the status never advertises SIMULATION as a fallback.
 *
 * SIMULATION is a mode an operator chooses, never one the system falls into.
 */
class UncommissionedTseStaysNormalTest {

    private val tseStatus = TseStatus()

    @AfterEach
    fun tearDown() {
        tseStatus.close()
        VirtualTseTestSupport.detachDevice()
    }

    @Test
    fun `an uncommissioned NORMAL TSE stays NORMAL, not SIMULATION`() {
        // Attached but never set up: startup cannot commission it, so configuration fails.
        VirtualTseTestSupport.attachDevice("v2")
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        tseStatus.setTimeAdminPin("98765")
        assertTrue(tseStatus.detectHardware())

        val configured = tseStatus.configureFromTseConfig(
            clientIdValue = "TER-001",
            timeAdminPinValue = "98765",
            modeValue = TseMode.NORMAL,
        )

        assertTrue(!configured, "an uncommissioned device cannot be configured operational")
        assertEquals(
            TseMode.NORMAL, tseStatus.getMode(),
            "a failed NORMAL config must stay NORMAL-not-operational, never fall into SIMULATION",
        )
    }

    @Test
    fun `explicit SIMULATION is still honoured`() {
        // The guard must not have taken away the operator's deliberate choice of SIMULATION.
        VirtualTseTestSupport.attachDevice("v2")
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        tseStatus.setTimeAdminPin("98765")
        assertTrue(tseStatus.detectHardware())

        tseStatus.configureFromTseConfig(
            clientIdValue = "TER-001",
            timeAdminPinValue = "98765",
            modeValue = TseMode.SIMULATION,
        )

        assertEquals(TseMode.SIMULATION, tseStatus.getMode(), "an explicit SIMULATION request stands")
    }
}
