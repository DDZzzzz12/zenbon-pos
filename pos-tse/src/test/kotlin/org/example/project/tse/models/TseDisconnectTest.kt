package org.example.project.tse.models

import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.tse.virtual.VirtualTseTestSupport
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/**
 * The Swissbit guidance manual (2.1) requires the ERS to unmount the TSE cleanly before it
 * is removed. disconnect() must therefore release the store and leave nothing behind — a
 * stale cached generation in particular, since swapping a TSE 2.0 for a TSE 1.x would
 * otherwise leave v2-only code paths active against a device that cannot serve them.
 */
class TseDisconnectTest {

    private val tseStatus = TseStatus()

    @AfterEach
    fun tearDown() = VirtualTseTestSupport.detachDevice()

    @Test
    fun `disconnect releases the store and clears connection state`() {
        VirtualTseTestSupport.attachDevice("v2")
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        tseStatus.setMode(TseMode.NORMAL)
        assertTrue(tseStatus.detectHardware())
        assertTrue(tseStatus.isConnected())
        assertTrue(tseStatus.isTseV2(), "the v2 device should be recognised while connected")

        tseStatus.disconnect()

        assertFalse(tseStatus.isConnected(), "disconnect must clear the connection")
        assertFalse(tseStatus.hasWormStoreInstance(), "the WormStore must be released")
        assertFalse(tseStatus.isTseV2(), "cached generation must not survive a disconnect")
    }

    @Test
    fun `disconnect is safe when nothing is connected`() {
        tseStatus.disconnect()
        assertFalse(tseStatus.isConnected())
    }
}
