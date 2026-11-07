package org.example.project.tse.tasks

import com.secureflashcard.wormapi.WormInitializationState
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.tse.models.TseStatus
import org.example.project.tse.virtual.VirtualTseTestSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/**
 * A fresh TSE 2.0 must come up through the vendor helper. The hand-rolled v1 sequence
 * cannot work here: it registers a client and runs a self test before tse_initialize,
 * and on v2 both require an already-initialized device.
 */
class TseSetupV2Test {

    private val tseStatus = TseStatus()

    @AfterEach
    fun tearDown() {
        tseStatus.close()
        VirtualTseTestSupport.detachDevice()
    }

    private fun connect(generation: String) {
        VirtualTseTestSupport.attachDevice(generation)
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        tseStatus.setMode(TseMode.NORMAL)
        tseStatus.setClientId("POS-TERMINAL-1")
        tseStatus.setTimeAdminPin("98765")
        tseStatus.setCredentialSeed("SwissbitSwissbit")
        assertTrue(tseStatus.detectHardware())
    }

    @Test
    fun `fresh TSE 2_0 initializes end to end`() {
        connect("v2")

        val ok = TseInitializationManager(tseStatus)
            .setUp(adminPin = "54321", adminPuk = "888888", credentialSeed = "SwissbitSwissbit", clientId = "POS-TERMINAL-1")

        assertTrue(ok, "initialize() should succeed on a fresh TSE 2.0")
        assertEquals(WormInitializationState.WORM_INIT_INITIALIZED, tseStatus.getInitializationState())
    }

    @Test
    fun `fresh TSE 2_0 ends transaction-ready`() {
        connect("v2")

        TseInitializationManager(tseStatus)
            .setUp(adminPin = "54321", adminPuk = "888888", credentialSeed = "SwissbitSwissbit", clientId = "POS-TERMINAL-1")

        val store = tseStatus.getWormStore()
        store.info().use { info ->
            assertTrue(info.hasValidTime(), "time must be set")
            assertTrue(info.hasPassedSelfTest(), "self test must have passed")
            assertEquals(1L, info.registeredClients(), "the client must be registered")
        }
        // The point of all of it: a transaction succeeds with no further setup.
        store.transaction_start("POS-TERMINAL-1", "data".toByteArray(), "Kassenbeleg-V1")
    }

    @Test
    fun `fresh TSE 1_x still initializes through the certified sequence`() {
        connect("v1")

        val ok = TseInitializationManager(tseStatus)
            .setUp(adminPin = "54321", adminPuk = "888888", credentialSeed = "SwissbitSwissbit", clientId = "POS-TERMINAL-1")

        assertTrue(ok, "the TSE 1.x path must be unaffected")
        assertEquals(WormInitializationState.WORM_INIT_INITIALIZED, tseStatus.getInitializationState())
    }
}
