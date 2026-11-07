package org.example.project.tse.operations

import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.admin.csp.DisableExportIfCspTestFailsOperation
import org.example.project.tse.operations.admin.ctss.CtssDisableOperation
import org.example.project.tse.operations.admin.ctss.CtssEnableOperation
import org.example.project.tse.virtual.VirtualTseTestSupport
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/**
 * On TSE 2.0 the CTSS interface and the CSP-export toggle no longer exist, and the SDK
 * calls became silent no-ops. Reporting plain success would tell an operator something
 * was enabled when it was not.
 */
class CapabilityGatingTest {

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
        // Real hardware forbids Admin login until the PUK is changed; the v1 CTSS path logs
        // in, so give it a device that permits that. The v2 cases return before any login.
        VirtualTseTestSupport.enableLogin(tseStatus.getWormStore())
    }

    private fun assertNotApplicable(success: Boolean, message: String, label: String) {
        assertFalse(success, "$label must not claim success for a feature that no longer exists")
        assertTrue(
            message.contains("2.0", ignoreCase = true) || message.contains("not applicable", ignoreCase = true),
            "$label message should explain why: got '$message'",
        )
    }

    @Test
    fun `ctss enable reports not-applicable on TSE 2_0`() {
        connect("v2")
        val result = CtssEnableOperation(adminPin = "12345").execute(tseStatus)
        assertNotApplicable(result.success, result.message, "CtssEnable")
    }

    @Test
    fun `ctss disable reports not-applicable on TSE 2_0`() {
        connect("v2")
        val result = CtssDisableOperation(adminPin = "12345").execute(tseStatus)
        assertNotApplicable(result.success, result.message, "CtssDisable")
    }

    @Test
    fun `csp export toggle reports not-applicable on TSE 2_0`() {
        connect("v2")
        val result = DisableExportIfCspTestFailsOperation(adminPin = "12345").execute(tseStatus)
        assertNotApplicable(result.success, result.message, "DisableExportIfCspTestFails")
    }

    @Test
    fun `ctss enable still works on TSE 1_x`() {
        connect("v1")
        val result = CtssEnableOperation(adminPin = "12345").execute(tseStatus)
        assertTrue(result.success, "the v1 behaviour must be unchanged")
    }
}
