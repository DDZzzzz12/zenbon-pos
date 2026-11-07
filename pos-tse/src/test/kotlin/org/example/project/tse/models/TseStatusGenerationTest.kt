package org.example.project.tse.models

import org.example.project.tse.virtual.VirtualTseTestSupport
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/**
 * The generation is read once at connect and cached: on TSE 2.0 every authenticated
 * call resets the 40-minute idle timer, so repeatedly probing info() to answer
 * "is this a v2?" would be both wasteful and behaviour-changing.
 */
class TseStatusGenerationTest {

    private val tseStatus = TseStatus()

    @AfterEach
    fun tearDown() {
        tseStatus.close()
        VirtualTseTestSupport.detachDevice()
    }

    @Test
    fun `detects a TSE 2_0 device on connect`() {
        VirtualTseTestSupport.attachDevice("v2")
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)

        assertTrue(tseStatus.detectHardware(), "virtual v2 device should be detected")
        assertTrue(tseStatus.isTseV2(), "TseStatus should report the device as TSE 2.0")
    }

    @Test
    fun `detects a TSE 1_x device on connect`() {
        VirtualTseTestSupport.attachDevice("v1")
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)

        assertTrue(tseStatus.detectHardware(), "virtual v1 device should be detected")
        assertFalse(tseStatus.isTseV2(), "TseStatus should report the device as TSE 1.x")
    }

    @Test
    fun `reports v1 when no device has been detected`() {
        // Safe default: nothing may take a v2-only path before a device is known.
        assertFalse(tseStatus.isTseV2())
    }

    @Test
    fun `forgets the generation on disconnect`() {
        VirtualTseTestSupport.attachDevice("v2")
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        assertTrue(tseStatus.detectHardware())
        assertTrue(tseStatus.isTseV2())

        tseStatus.disconnect()

        // Otherwise swapping a TSE 2.0 for a TSE 1.x would leave v2 paths active.
        assertFalse(tseStatus.isTseV2(), "generation must not survive a disconnect")
    }
}
