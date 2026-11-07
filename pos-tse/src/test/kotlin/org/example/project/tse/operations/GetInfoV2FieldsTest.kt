package org.example.project.tse.operations

import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.admin.status.GetInfoOperation
import org.example.project.tse.virtual.VirtualTseTestSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/**
 * The operator needs to know which generation is attached and who is logged in: on TSE 2.0
 * a stuck TimeAdmin session silently blocks exports, and that is invisible without this.
 */
class GetInfoV2FieldsTest {

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
        assertTrue(tseStatus.detectHardware())
    }

    @Test
    fun `reports TSE 2_0 generation and login state`() {
        connect("v2")

        val info = GetInfoOperation(adminPin = "12345").execute(tseStatus)

        assertTrue(info.isTseV2, "a v2 device must be reported as such")
        assertEquals("WORM_USER_UNAUTHENTICATED", info.loggedInUser)
        assertEquals(0, info.loggedInUserAutoLogOutTimeout)
        assertFalse(info.hasChangedAdminPuk, "a fresh device has not had its PUK changed")
    }

    @Test
    fun `reports TSE 1_x as not v2`() {
        connect("v1")

        val info = GetInfoOperation(adminPin = "12345").execute(tseStatus)

        assertFalse(info.isTseV2)
    }

    @Test
    fun `reports capacity without Int overflow`() {
        connect("v2")

        val info = GetInfoOperation(adminPin = "12345").execute(tseStatus)

        // The virtual device reports a 7 GB capacity, which does not fit in an Int.
        // Truncating it produced a negative figure on every real (8 GB) TSE.
        assertTrue(info.capacity > 0, "capacity must not overflow: got ${info.capacity}")
        assertTrue(info.size >= 0, "size must not overflow: got ${info.size}")
    }
}
