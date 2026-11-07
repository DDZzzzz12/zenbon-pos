package org.example.project.tse.models

import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.tse.virtual.VirtualTseTestSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/**
 * Configuring the TSE must record the client ID on [TseStatus], not just hand it to the
 * initialization manager.
 *
 * Sixteen places read `tseStatus.getClientId()` — the time- and transaction-filtered exports,
 * the simulated signing path, the maintenance self test — and all of them saw null in the
 * in-process LOCAL deployment. Only the dedicated-server deployment ever set it, through
 * ApplyConfigFromMainServer, which LOCAL mode does not go through. The manager resolved the
 * id and kept it to itself, so the two disagreed.
 */
class ConfigureRecordsTheClientIdTest {

    private val tseStatus = TseStatus()

    @AfterEach
    fun tearDown() {
        tseStatus.close()
        VirtualTseTestSupport.detachDevice()
    }

    @Test
    fun `configuring in SIMULATION mode records the client id`() {
        // SIMULATION needs no hardware, which keeps this about the plumbing and nothing else.
        assertNull(tseStatus.getClientId(), "precondition: nothing has set it yet")

        tseStatus.configureFromTseConfig(
            clientIdValue = "TER-001",
            timeAdminPinValue = "98765",
            modeValue = TseMode.SIMULATION,
        )

        assertEquals("TER-001", tseStatus.getClientId())
    }

    @Test
    fun `configuring against real hardware records the client id`() {
        VirtualTseTestSupport.attachDevice("v2")
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        tseStatus.setTimeAdminPin("98765")
        assertTrue(tseStatus.detectHardware())
        tseStatus.getWormStore().tse_setup_ext(
            "SwissbitSwissbit".toByteArray(), "888888".toByteArray(),
            "54321".toByteArray(), "98765".toByteArray(), "TER-001",
        )

        tseStatus.configureFromTseConfig(
            clientIdValue = "TER-001",
            timeAdminPinValue = "98765",
            modeValue = TseMode.NORMAL,
        )

        assertEquals(
            "TER-001", tseStatus.getClientId(),
            "the NORMAL path is the one LOCAL mode actually takes",
        )
    }

    @Test
    fun `a null client id does not erase one already set`() {
        tseStatus.setClientId("TER-001")

        // Callers that have nothing to offer must leave the existing value alone rather than
        // clearing it — a later re-configure should not undo a commissioning.
        tseStatus.configureFromTseConfig(
            clientIdValue = null,
            timeAdminPinValue = "98765",
            modeValue = TseMode.SIMULATION,
        )

        assertEquals("TER-001", tseStatus.getClientId())
    }
}
